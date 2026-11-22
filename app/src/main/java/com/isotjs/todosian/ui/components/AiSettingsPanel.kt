package com.isotjs.todosian.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.R
import com.isotjs.todosian.data.ai.AiCleanup
import com.isotjs.todosian.data.ai.AiModel
import com.isotjs.todosian.data.ai.AiModelInfo
import com.isotjs.todosian.data.ai.AiStatus
import com.isotjs.todosian.data.settings.AppSettings
import com.isotjs.todosian.data.settings.AppSettingsRepository

private const val AICORE_PACKAGE = "com.google.android.aicore"

/** The chosen variant from the setting; null = automatic. */
internal fun AppSettings.preferredAiModel(): AiModel? = AiModel.entries.firstOrNull { it.name == aiModel }

/**
 * Everything about Gemini Nano on this device, one row each: AICore, both model variants with
 * what is there, what can be loaded and what is loading (with cancel), the variant to use and
 * the switch for the button. Used in the settings and over a task sheet.
 */
@Composable
fun ColumnScope.AiSettingsPanel(settings: AppSettings, appSettingsRepository: AppSettingsRepository) {
    val context = LocalContext.current
    val models by AiCleanup.models.collectAsStateWithLifecycle()
    val status by AiCleanup.status.collectAsStateWithLifecycle()
    LaunchedEffect(settings.aiModel) {
        AiCleanup.preferred = settings.preferredAiModel()
        AiCleanup.refresh()
    }
    val aiCoreVersion = remember { aiCoreVersion(context) }
    val active = AiCleanup.activeModel()
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)

    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_ai_aicore)) },
        supportingContent = {
            Text(
                text = aiCoreVersion?.let { stringResource(R.string.settings_ai_aicore_installed, it) }
                    ?: stringResource(R.string.settings_ai_aicore_missing),
            )
        },
        leadingContent = { Icon(imageVector = Icons.Filled.Memory, contentDescription = null) },
        trailingContent = if (aiCoreVersion != null) {
            { TextButton(onClick = { openAiCoreSettings(context) }) { Text(text = stringResource(R.string.settings_ai_aicore_manage)) } }
        } else {
            null
        },
        colors = colors,
    )

    AiModel.entries.forEach { model ->
        HorizontalDivider()
        AiModelRow(
            model = model,
            info = models[model] ?: AiModelInfo(),
            inUse = model == active && models[model]?.status == AiStatus.Available,
            context = context,
        )
    }

    HorizontalDivider()
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_ai_model_choice)) },
        supportingContent = {
            Column {
                Text(text = stringResource(R.string.settings_ai_model_choice_subtitle, modelLabel(active)))
                Spacer(modifier = Modifier.height(8.dp))
                val options = listOf<AiModel?>(null) + AiModel.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = settings.preferredAiModel() == option,
                            onClick = { appSettingsRepository.setAiModel(option?.name.orEmpty()) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) {
                            Text(text = option?.let { modelShortLabel(it) } ?: stringResource(R.string.settings_ai_model_auto))
                        }
                    }
                }
            }
        },
        leadingContent = { Icon(imageVector = Icons.Filled.AutoAwesome, contentDescription = null) },
        colors = colors,
    )

    HorizontalDivider()
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_ai_cleanup)) },
        supportingContent = { Text(text = stringResource(R.string.settings_ai_cleanup_subtitle)) },
        leadingContent = { BadgedSparkle(badge = "AI", filled = true, tint = MaterialTheme.colorScheme.tertiary) },
        trailingContent = {
            Switch(
                checked = settings.aiCleanupEnabled && status != AiStatus.Unavailable,
                onCheckedChange = { appSettingsRepository.setAiCleanupEnabled(it) },
                enabled = status != AiStatus.Unavailable,
            )
        },
        colors = colors,
    )

    HorizontalDivider()
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_ai_check_again)) },
        leadingContent = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = { AiCleanup.refresh() }) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = stringResource(R.string.settings_ai_check_again))
            }
        },
        colors = colors,
    )

    HorizontalDivider()
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.settings_ai_note)) },
        supportingContent = { Text(text = stringResource(R.string.settings_ai_delete_note)) },
        leadingContent = { Icon(imageVector = Icons.Filled.Info, contentDescription = null) },
        colors = colors,
    )
}

@Composable
private fun AiModelRow(model: AiModel, info: AiModelInfo, inUse: Boolean, context: Context) {
    val status = info.status
    val statusText = when (status) {
        AiStatus.Unknown -> stringResource(R.string.settings_ai_status_unknown)
        AiStatus.Available -> info.name?.let { stringResource(R.string.settings_ai_model_present_named, it) }
            ?: stringResource(R.string.settings_ai_model_present)
        AiStatus.Downloadable -> stringResource(R.string.settings_ai_model_downloadable)
        AiStatus.Preparing -> stringResource(R.string.settings_ai_status_preparing)
        AiStatus.Unavailable -> stringResource(R.string.settings_ai_model_unavailable)
        is AiStatus.Downloading -> {
            val bytes = status.bytes
            val total = status.total
            when {
                bytes != null && total != null -> stringResource(
                    R.string.settings_ai_model_downloading_of,
                    Formatter.formatShortFileSize(context, bytes),
                    Formatter.formatShortFileSize(context, total),
                )
                bytes != null && bytes > 0 -> stringResource(
                    R.string.settings_ai_status_downloading_bytes,
                    Formatter.formatShortFileSize(context, bytes),
                )
                else -> stringResource(R.string.settings_ai_status_downloading)
            }
        }
    }
    ListItem(
        headlineContent = { Text(text = modelLabel(model)) },
        supportingContent = {
            Column {
                Text(text = stringResource(modelDescription(model)), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (inUse) "$statusText · ${stringResource(R.string.settings_ai_model_in_use)}" else statusText,
                    color = when (status) {
                        AiStatus.Available -> MaterialTheme.colorScheme.tertiary
                        AiStatus.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (status is AiStatus.Downloading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val bytes = status.bytes
                    val total = status.total
                    if (bytes != null && total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = when (status) {
                    AiStatus.Available -> Icons.Filled.CheckCircle
                    AiStatus.Unavailable -> Icons.Filled.RemoveCircleOutline
                    else -> Icons.Filled.CloudDownload
                },
                contentDescription = null,
                tint = if (status == AiStatus.Available) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = when (status) {
            AiStatus.Downloadable -> {
                { TextButton(onClick = { AiCleanup.download(model) }) { Text(text = stringResource(R.string.settings_ai_download)) } }
            }
            is AiStatus.Downloading -> {
                { TextButton(onClick = { AiCleanup.cancelDownload(model) }) { Text(text = stringResource(R.string.settings_ai_download_cancel)) } }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The panel full screen over a task sheet, so the typed text stays where it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsDialog(settings: AppSettings, appSettingsRepository: AppSettingsRepository, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.settings_section_ai)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ai_settings_close))
                        }
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    AiSettingsPanel(settings = settings, appSettingsRepository = appSettingsRepository)
                }
            }
        }
    }
}

@Composable
private fun modelLabel(model: AiModel): String = stringResource(
    when (model) {
        AiModel.FULL -> R.string.settings_ai_model_full
        AiModel.FAST -> R.string.settings_ai_model_fast
        AiModel.REWRITE -> R.string.settings_ai_model_rewrite
    },
)

@Composable
private fun modelShortLabel(model: AiModel): String = stringResource(
    when (model) {
        AiModel.FULL -> R.string.settings_ai_model_full_short
        AiModel.FAST -> R.string.settings_ai_model_fast_short
        AiModel.REWRITE -> R.string.settings_ai_model_rewrite_short
    },
)

private fun modelDescription(model: AiModel): Int = when (model) {
    AiModel.FULL -> R.string.settings_ai_model_full_description
    AiModel.FAST -> R.string.settings_ai_model_fast_description
    AiModel.REWRITE -> R.string.settings_ai_model_rewrite_description
}

private fun aiCoreVersion(context: Context): String? = try {
    context.packageManager.getPackageInfo(AICORE_PACKAGE, 0).versionName ?: "?"
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun openAiCoreSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", AICORE_PACKAGE, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
