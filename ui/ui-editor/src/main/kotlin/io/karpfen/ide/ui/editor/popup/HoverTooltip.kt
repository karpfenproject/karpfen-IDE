/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * The hover tooltip below or above [anchor]. It shows the diagnostics at the position with severity icons if there are
 * any. Otherwise it shows the language hover text in the editor's [textStyle]. Long content scrolls.
 *
 * A click on the tooltip dismisses it through [onDismiss]. The pointer entering and leaving it is reported to
 * [onPointerOver]. So the editor state keeps the tooltip while the pointer is on it, and hides it when the pointer
 * moves on or the user types.
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

/** One diagnostic, shown with its severity icon and message. */
@Composable
private fun DiagnosticLine(diagnostic: Diagnostic, theme: EditorTheme) {
    val icon = SeverityIcon.of(diagnostic.severity)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(icon.image, contentDescription = icon.description, modifier = Modifier.size(14.dp))
        BasicText(diagnostic.message, style = popupLabelStyle(theme))
    }
}

/**
 * The icon of a diagnostic severity in the tooltip. The tooltip uses the same severity icons as the Problems view.
 *
 * @property image the severity icon without tint.
 * @property description the accessible name of the severity.
 */
internal data class SeverityIcon(val image: ImageVector, val description: String) {
    /** The icon of each severity. */
    companion object {
        /** Returns the icon of [severity]. Hints use the info icon. */
        fun of(severity: Severity): SeverityIcon = when (severity) {
            Severity.ERROR -> SeverityIcon(KarpfenIcons.Error, "Error")
            Severity.WARNING -> SeverityIcon(KarpfenIcons.Warning, "Warning")
            Severity.INFO -> SeverityIcon(KarpfenIcons.Info, "Information")
            Severity.HINT -> SeverityIcon(KarpfenIcons.Info, "Hint")
        }
    }
}
