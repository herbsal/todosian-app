package com.isotjs.todosian.data.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Whether Gemini Nano can clean up text on this device. */
sealed interface AiStatus {
    /** Not checked yet. */
    data object Unknown : AiStatus

    /** No Gemini Nano here (device not supported, unlocked bootloader, AICore missing or too old). */
    data object Unavailable : AiStatus

    /** Supported, but the model is not on the device yet. Loading it needs the person's yes. */
    data object Downloadable : AiStatus

    /** The model is being loaded; [bytes] so far, when known. */
    data class Downloading(val bytes: Long?) : AiStatus

    /** AICore is still setting up after an update or restart. */
    data object Preparing : AiStatus

    data object Available : AiStatus
}

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
    private val _status = MutableStateFlow<AiStatus>(AiStatus.Unknown)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private var model: GenerativeModel? = null
    private var downloadJob: Job? = null
    private var warmedUp = false

    private fun model(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    /** Checks again what the device offers; cheap, and AICore can change with an update. */
    fun refresh() {
        scope.launch { check() }
    }

    private suspend fun check(): AiStatus {
        if (_status.value is AiStatus.Downloading && downloadJob?.isActive == true) return _status.value
        val status = try {
            when (model().checkStatus()) {
                FeatureStatus.AVAILABLE -> AiStatus.Available
                FeatureStatus.DOWNLOADABLE -> AiStatus.Downloadable
                FeatureStatus.DOWNLOADING -> AiStatus.Downloading(null)
                else -> AiStatus.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano status not available", e)
            if (isPreparing(e)) AiStatus.Preparing else AiStatus.Unavailable
        }
        _status.value = status
        if (status == AiStatus.Available && !warmedUp) {
            warmedUp = true
            runCatching { model().warmup() }
        }
        return status
    }

    /** Loads the model through AICore. Only after the person agreed. */
    fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _status.value = AiStatus.Downloading(null)
            try {
                model().download().collect { step ->
                    when (step) {
                        is DownloadStatus.DownloadStarted -> _status.value = AiStatus.Downloading(0)
                        is DownloadStatus.DownloadProgress -> _status.value = AiStatus.Downloading(step.totalBytesDownloaded)
                        is DownloadStatus.DownloadFailed -> {
                            Log.w(TAG, "Gemini Nano download failed", step.e)
                            _status.value = AiStatus.Downloadable
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano download failed", e)
            }
            check()
        }
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
        if (check() != AiStatus.Available) return AiCleanupResult.Failed(AiFailure.NOT_READY)
        val masked = AiTextGuard.mask(text, markers)
        val prompt = AiCleanupPrompt.build(masked.text)
        return mutex.withLock {
            try {
                val answer = withTimeout(TIMEOUT_MS) { generate(prompt, retryWhenBusy = true) }
                val restored = AiTextGuard.restore(AiCleanupPrompt.answerOf(answer), masked)
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

    private suspend fun generate(prompt: String, retryWhenBusy: Boolean): String {
        return try {
            val response = model().generateContent(
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
                generate(prompt, retryWhenBusy = false)
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
