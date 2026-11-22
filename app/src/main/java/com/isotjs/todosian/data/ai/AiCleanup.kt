package com.isotjs.todosian.data.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.isotjs.todosian.utils.TaskMarkers
import com.isotjs.todosian.utils.ai.AiCleanupPrompt
import com.isotjs.todosian.utils.ai.AiTextGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Whether a Gemini Nano model can clean up text on this device. */
sealed interface AiStatus {
    /** Not checked yet. */
    data object Unknown : AiStatus

    /** Not here (device not supported, unlocked bootloader, AICore missing or too old). */
    data object Unavailable : AiStatus

    /** Supported, but this model is not on the device yet. Loading it needs the person's yes. */
    data object Downloadable : AiStatus

    /** The model is being loaded: [bytes] of [total] so far, when known. */
    data class Downloading(val bytes: Long?, val total: Long?) : AiStatus

    /** AICore is still setting up after an update or restart. */
    data object Preparing : AiStatus

    data object Available : AiStatus
}

/**
 * The Gemini Nano variants the Prompt API offers on consumer devices. AICore may already hold one
 * of them (e.g. for Pixel features) and not the other, so each is checked on its own.
 */
enum class AiModel {
    /** Prompt API, model preference FULL: own instruction with examples. */
    FULL,

    /** Prompt API, model preference FAST. */
    FAST,

    /**
     * Rewriting API ("shorten"): a small task adapter on the Gemini Nano base model AICore already
     * holds for system features, so it may need only a small download.
     */
    REWRITE,
}

/** What AICore says about one [AiModel]; [name] is the base model's name once it is there. */
data class AiModelInfo(val status: AiStatus = AiStatus.Unknown, val name: String? = null)

sealed interface AiCleanupResult {
    data class Cleaned(val text: String) : AiCleanupResult

    /** Nothing to clean up: the model gave the text back as it was. */
    data object Unchanged : AiCleanupResult

    data class Failed(val reason: AiFailure) : AiCleanupResult
}

enum class AiFailure { BUSY, DAILY_LIMIT, TIMEOUT, UNSAFE, NOT_READY, LEFT_APP, OTHER }

/**
 * Gemini Nano on the device through ML Kit GenAI (Prompt API). Text never leaves the phone:
 * AICore runs the model, and only AICore loads it. The app itself asks before any download.
 */
object AiCleanup {
    private const val TAG = "AiCleanup"
    private const val TIMEOUT_MS = 8_000L
    private const val BUSY_RETRY_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _models = MutableStateFlow(AiModel.entries.associateWith { AiModelInfo() })

    /** Every variant with its status, for the settings. */
    val models: StateFlow<Map<AiModel, AiModelInfo>> = _models.asStateFlow()

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Unknown)

    /** The status of the model in use (see [activeModel]). */
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    /** The person's choice; null = use whichever variant is on the device, preferring [AiModel.FULL]. */
    @Volatile
    var preferred: AiModel? = null
        set(value) {
            field = value
            publishActive()
        }

    private val clients = mutableMapOf<AiModel, GenerativeModel>()
    private var rewriter: Rewriter? = null
    private var appContext: Context? = null

    /** Called once from the application: the Rewriting API needs a context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun rewriter(): Rewriter = synchronized(clients) {
        rewriter ?: Rewriting.getClient(
            RewriterOptions.builder(checkNotNull(appContext) { "AiCleanup.init missing" })
                .setOutputType(RewriterOptions.OutputType.SHORTEN)
                .setLanguage(RewriterOptions.Language.GERMAN)
                .build(),
        ).also { rewriter = it }
    }
    private val downloadJobs = ConcurrentHashMap<AiModel, Job>()
    private val warmedUp: MutableSet<AiModel> = ConcurrentHashMap.newKeySet()

    private fun client(model: AiModel): GenerativeModel = synchronized(clients) {
        clients.getOrPut(model) {
            val config = generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = when (model) {
                        AiModel.FULL -> ModelPreference.FULL
                        AiModel.FAST, AiModel.REWRITE -> ModelPreference.FAST
                    }
                }
            }
            Generation.getClient(config)
        }
    }

    /** The variant cleanups use: the chosen one, else one already on the device, else [AiModel.FULL]. */
    fun activeModel(): AiModel {
        preferred?.let { return it }
        val models = _models.value
        // A download is offered for the rewriting adapter first: on top of a base model AICore
        // already holds, it is the smallest.
        val downloadOrder = listOf(AiModel.REWRITE, AiModel.FULL, AiModel.FAST)
        return AiModel.entries.firstOrNull { models[it]?.status == AiStatus.Available }
            ?: downloadOrder.firstOrNull { models[it]?.status is AiStatus.Downloading }
            ?: downloadOrder.firstOrNull { models[it]?.status == AiStatus.Downloadable }
            ?: AiModel.FULL
    }

    private fun publishActive() {
        val models = _models.value
        _status.value = when {
            models.values.all { it.status == AiStatus.Unknown } -> AiStatus.Unknown
            models.values.all { it.status == AiStatus.Unavailable } -> AiStatus.Unavailable
            else -> models[activeModel()]?.status ?: AiStatus.Unknown
        }
    }

    /** Checks again what the device offers; cheap, and AICore can change with an update. */
    fun refresh() {
        scope.launch { checkAll() }
    }

    private suspend fun checkAll() {
        AiModel.entries.forEach { check(it) }
        val active = activeModel()
        if (active != AiModel.REWRITE && _models.value[active]?.status == AiStatus.Available && warmedUp.add(active)) {
            runCatching { client(active).warmup() }
        }
    }

    private suspend fun check(model: AiModel): AiStatus {
        if (downloadJobs[model]?.isActive == true) return _models.value[model]?.status ?: AiStatus.Unknown
        val status = try {
            val featureStatus = if (model == AiModel.REWRITE) rewriter().checkFeatureStatus().await() else client(model).checkStatus()
            when (featureStatus) {
                FeatureStatus.AVAILABLE -> AiStatus.Available
                FeatureStatus.DOWNLOADABLE -> AiStatus.Downloadable
                FeatureStatus.DOWNLOADING -> AiStatus.Downloading(null, null)
                else -> AiStatus.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano status for $model not available", e)
            if (isPreparing(e)) AiStatus.Preparing else AiStatus.Unavailable
        }
        val name = if (status == AiStatus.Available && model != AiModel.REWRITE) {
            runCatching { client(model).getBaseModelName() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        setModel(model, AiModelInfo(status, name))
        return status
    }

    private fun setModel(model: AiModel, info: AiModelInfo) {
        _models.update { it + (model to info) }
        publishActive()
    }

    /** Loads [model] through AICore. Only after the person agreed. */
    fun download(model: AiModel = activeModel()) {
        if (downloadJobs[model]?.isActive == true) return
        downloadJobs[model] = scope.launch {
            var total: Long? = null
            setModel(model, AiModelInfo(AiStatus.Downloading(null, null)))
            try {
                downloadFlow(model).collect { step ->
                    when (step) {
                        is DownloadStatus.DownloadStarted -> {
                            total = step.bytesToDownload.takeIf { it > 0 }
                            setModel(model, AiModelInfo(AiStatus.Downloading(0, total)))
                        }
                        is DownloadStatus.DownloadProgress ->
                            setModel(model, AiModelInfo(AiStatus.Downloading(step.totalBytesDownloaded, total)))
                        is DownloadStatus.DownloadFailed -> Log.w(TAG, "Gemini Nano download failed", step.e)
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano download failed", e)
            } finally {
                downloadJobs.remove(model)
            }
            check(model)
        }
    }

    private fun downloadFlow(model: AiModel): Flow<DownloadStatus> =
        if (model != AiModel.REWRITE) {
            client(model).download()
        } else {
            callbackFlow {
                rewriter().downloadFeature(object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) {
                        trySend(DownloadStatus.DownloadStarted(bytesToDownload))
                    }

                    override fun onDownloadProgress(totalBytesDownloaded: Long) {
                        trySend(DownloadStatus.DownloadProgress(totalBytesDownloaded))
                    }

                    override fun onDownloadCompleted() {
                        close()
                    }

                    override fun onDownloadFailed(e: GenAiException) {
                        close(e)
                    }
                })
                awaitClose { }
            }
        }

    /**
     * Stops following the download of [model]. The Prompt API has no call to cancel or delete a
     * model; AICore may finish a download it already started and keeps models other apps use.
     */
    fun cancelDownload(model: AiModel) {
        downloadJobs.remove(model)?.cancel()
        scope.launch { check(model) }
    }

    /**
     * Cleans up one task: filler words and repeats go, tags, fields, list words and links stay as
     * they are. [verify] gets the cleaned text and may still reject it (e.g. other date words).
     */
    suspend fun clean(
        text: String,
        markers: TaskMarkers = TaskMarkers.Default,
        verify: (String) -> Boolean = { true },
    ): AiCleanupResult {
        if (text.isBlank()) return AiCleanupResult.Unchanged
        val model = activeModel()
        if (check(model) != AiStatus.Available) return AiCleanupResult.Failed(AiFailure.NOT_READY)
        val masked = AiTextGuard.mask(text, markers)
        val rewriting = model == AiModel.REWRITE
        return mutex.withLock {
            try {
                val answer = withTimeout(TIMEOUT_MS) {
                    if (rewriting) {
                        rewrite(masked.textWithoutTrailing, retryWhenBusy = true)
                    } else {
                        AiCleanupPrompt.answerOf(generate(client(model), AiCleanupPrompt.build(masked.text), retryWhenBusy = true))
                    }
                }
                val restored = AiTextGuard.restore(answer, masked, trailingOmitted = rewriting)
                when {
                    restored == null -> AiCleanupResult.Failed(AiFailure.UNSAFE)
                    AiTextGuard.isUnchanged(text, restored) -> AiCleanupResult.Unchanged
                    !verify(restored) -> AiCleanupResult.Failed(AiFailure.UNSAFE)
                    else -> AiCleanupResult.Cleaned(restored)
                }
            } catch (e: TimeoutCancellationException) {
                AiCleanupResult.Failed(AiFailure.TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                Log.w(TAG, "Cleanup failed: ${e.errorCode}", e)
                AiCleanupResult.Failed(failureOf(e))
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed", e)
                AiCleanupResult.Failed(AiFailure.OTHER)
            }
        }
    }

    private suspend fun generate(client: GenerativeModel, prompt: String, retryWhenBusy: Boolean): String {
        return try {
            val response = client.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.1f
                    topK = 1
                    candidateCount = 1
                    maxOutputTokens = 96
                },
            )
            response.candidates.firstOrNull()?.text.orEmpty()
        } catch (e: GenAiException) {
            if (retryWhenBusy && e.errorCode == GenAiException.ErrorCode.BUSY) {
                delay(BUSY_RETRY_MS)
                generate(client, prompt, retryWhenBusy = false)
            } else {
                throw e
            }
        }
    }

    // The rewriting adapter only sees the words; fields and tags at the end are added back after.
    private suspend fun rewrite(words: String, retryWhenBusy: Boolean): String {
        return try {
            val result = rewriter().runInference(RewritingRequest.builder(words).build()).await()
            result.results.firstOrNull()?.text?.trim().orEmpty()
        } catch (e: GenAiException) {
            if (retryWhenBusy && e.errorCode == GenAiException.ErrorCode.BUSY) {
                delay(BUSY_RETRY_MS)
                rewrite(words, retryWhenBusy = false)
            } else {
                throw e
            }
        }
    }

    private fun failureOf(e: GenAiException): AiFailure = when (e.errorCode) {
        GenAiException.ErrorCode.BUSY -> AiFailure.BUSY
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> AiFailure.DAILY_LIMIT
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> AiFailure.LEFT_APP
        GenAiException.ErrorCode.NOT_AVAILABLE -> AiFailure.NOT_READY
        else -> AiFailure.OTHER
    }

    // AICore right after an update or reset: "601-BINDING_FAILURE" or "606-FEATURE_NOT_FOUND".
    private fun isPreparing(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return "BINDING_FAILURE" in message || "FEATURE_NOT_FOUND" in message
    }
}
