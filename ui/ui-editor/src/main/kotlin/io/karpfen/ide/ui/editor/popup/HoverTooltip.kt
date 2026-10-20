package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import io.karpfen.ide.ui.editor.state.HoverContent
import io.karpfen.ide.ui.editor.theme.EditorTheme

/** The hover tooltip (spec §7.2): diagnostics with severity icons, otherwise the language hover text, near [anchor]. */
@Composable
internal fun HoverTooltip(content: HoverContent, anchor: Rect, theme: EditorTheme, onDismiss: () -> Unit): Unit =
    TODO("WP-EDITOR-POPUPS: hover tooltip")
