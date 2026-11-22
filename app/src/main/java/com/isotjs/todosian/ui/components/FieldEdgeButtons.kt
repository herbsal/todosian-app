package com.isotjs.todosian.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** How far the round buttons on a text field's right edge stick out of it. */
private val EdgeInset = 18.dp

/** A text field tall enough for two edge buttons above each other. */
internal val EdgeFieldMinHeight = 88.dp

/**
 * A task's text field with its round buttons (tag recognition, AI cleanup) sitting on its right
 * edge, half inside and half outside, above each other, so they take no width from the words.
 * Give the field [EdgeFieldTrailingSpace] as trailing icon and [EdgeFieldMinHeight] as minimum height.
 */
@Composable
internal fun FieldWithEdgeButtons(
    buttons: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth().padding(end = EdgeInset)) {
        field()
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = EdgeInset).padding(top = 4.dp),
            content = buttons,
        )
    }
}

/** Keeps the field's words clear of the edge buttons. */
@Composable
internal fun EdgeFieldTrailingSpace() {
    Spacer(modifier = Modifier.width(EdgeInset))
}

/** A small round button on a text field's edge; [selected] fills it (e.g. recognition on). */
@Composable
internal fun EdgeIconButton(
    onClick: () -> Unit,
    selected: Boolean,
    description: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Small on purpose, to fit on the field's edge; the field around it keeps taps apart.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            border = BorderStroke(1.dp, if (selected) colors.secondaryContainer else colors.outlineVariant),
            modifier = Modifier.size(38.dp).semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

/** One four-pointed star, without the small stars of the usual sparkle, so a badge fits beside it. */
internal val SingleSparkleOutlined: ImageVector by lazy { singleSparkle(filled = false) }
internal val SingleSparkleFilled: ImageVector by lazy { singleSparkle(filled = true) }

private fun singleSparkle(filled: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (filled) "SingleSparkleFilled" else "SingleSparkleOutlined",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(10f, 22f)
        curveToRelative(0.7f, -4f, 2.1f, -6.6f, 7f, -7.5f)
        curveToRelative(-4.9f, -0.9f, -6.3f, -3.5f, -7f, -7.7f)
        curveToRelative(-0.7f, 4.2f, -2.1f, 6.8f, -7f, 7.7f)
        curveToRelative(4.9f, 0.9f, 6.3f, 3.5f, 7f, 7.5f)
        close()
    }.build()
