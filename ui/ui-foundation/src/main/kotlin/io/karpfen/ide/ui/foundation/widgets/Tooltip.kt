package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/**
 * Shows [tooltip] near the pointer after hovering [content] for [TooltipDefaults.DELAY_MILLIS]; none when null.
 * [modifier] applies to the box wrapping [content] in both cases. Uses the desktop `TooltipArea`, which Compose still
 * marks experimental (no stable desktop tooltip exists).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTooltip(tooltip: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (tooltip == null) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        modifier = modifier,
        tooltip = {
            val shape = RoundedCornerShape(4.dp)
            Surface(
                shape = shape,
                color = KarpfenTheme.colors.panel,
                shadowElevation = 4.dp,
                modifier = Modifier.border(1.dp, KarpfenTheme.colors.border, shape),
            ) {
                Text(
                    text = tooltip,
                    style = KarpfenTheme.typography.uiSmall,
                    color = KarpfenTheme.colors.text,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = TooltipDefaults.DELAY_MILLIS,
        content = content,
    )
}

/** Timing of [WithTooltip]. */
internal object TooltipDefaults {
    /** Hover time before a tooltip appears. */
    const val DELAY_MILLIS: Int = 600
}
