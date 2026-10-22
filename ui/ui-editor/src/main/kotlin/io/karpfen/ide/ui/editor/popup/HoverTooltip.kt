package io.karpfen.ide.ui.editor.popup

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.ui.editor.state.HoverContent
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons

/**
 * The hover tooltip below (or above) [anchor]: the diagnostics at the position with severity icons if there
 * are any, otherwise the language hover text in the editor's [textStyle]. Long content scrolls. A click on the tooltip
 * dismisses it ([onDismiss]); the pointer entering and leaving it is reported to [onPointerOver], so the editor state
 * keeps it while the pointer is on it and hides it when the pointer moves on or the user types.
 */
@Composable
internal fun HoverTooltip(
    content: HoverContent,
    anchor: Rect,
    theme: EditorTheme,
    textStyle: TextStyle,
    onDismiss: () -> Unit,
    onPointerOver: (Boolean) -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val pointerOver by rememberUpdatedState(onPointerOver)
    AnchoredPopupLayout(anchor) {
        PopupSurface(
            theme,
            Modifier
                .widthIn(max = 560.dp)
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            when (awaitPointerEvent().type) {
                                PointerEventType.Enter -> pointerOver(true)
                                PointerEventType.Exit -> pointerOver(false)
                            }
                        }
                    }
                }
                .testTag(EditorPopupTags.HOVER),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (content.diagnostics.isNotEmpty()) {
                    content.diagnostics.forEach { DiagnosticLine(it, theme) }
                } else {
                    content.hover?.let { hover -> BasicText(hover.text, style = textStyle.merge(TextStyle(color = theme.foreground))) }
                }
            }
        }
    }
}

/** One diagnostic: its severity icon and message. */
@Composable
private fun DiagnosticLine(diagnostic: Diagnostic, theme: EditorTheme) {
    val icon = SeverityIcon.of(diagnostic.severity)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(icon.image, contentDescription = icon.description, modifier = Modifier.size(14.dp))
        BasicText(diagnostic.message, style = popupLabelStyle(theme))
    }
}

/**
 * The icon of a diagnostic severity in the tooltip (the shared severity icons of the Problems view).
 *
 * @property image the untinted severity icon.
 * @property description the severity's accessible name.
 */
internal data class SeverityIcon(val image: ImageVector, val description: String) {
    /** The icon of each severity. */
    companion object {
        /** The icon of [severity]; hints share the info icon. */
        fun of(severity: Severity): SeverityIcon = when (severity) {
            Severity.ERROR -> SeverityIcon(KarpfenIcons.Error, "Error")
            Severity.WARNING -> SeverityIcon(KarpfenIcons.Warning, "Warning")
            Severity.INFO -> SeverityIcon(KarpfenIcons.Info, "Information")
            Severity.HINT -> SeverityIcon(KarpfenIcons.Info, "Hint")
        }
    }
}
