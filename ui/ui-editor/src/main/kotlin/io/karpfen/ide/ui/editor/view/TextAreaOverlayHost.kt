package io.karpfen.ide.ui.editor.view

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import io.karpfen.ide.ui.editor.overlay.EditorOverlayHost
import io.karpfen.ide.ui.editor.state.EditorPopupModel
import io.karpfen.ide.ui.editor.theme.EditorTheme
import io.karpfen.ide.ui.editor.view.geometry.TextAreaFrame

/**
 * The [EditorOverlayHost] of `CodeEditor`: geometry in text-area coordinates from the current [TextAreaFrame]. Every
 * member reads Compose state (theme, style, the frame's geometry and scroll position, the carets), so popups that use
 * it recompose when the caret moves or the text scrolls.
 *
 * @param frame the text area at the current scroll position, null before the first layout.
 * @param primaryCaret the primary caret's offset in the frame's text.
 * @param caretWidth the caret's width in pixels.
 */
@Stable
internal class TextAreaOverlayHost(
    override val state: EditorPopupModel,
    private val themeState: State<EditorTheme>,
    private val textStyleState: State<TextStyle>,
    private val frame: () -> TextAreaFrame?,
    private val primaryCaret: () -> Int,
    private val caretWidth: Float,
) : EditorOverlayHost {

    override val theme: EditorTheme get() = themeState.value

    override val textStyle: TextStyle get() = textStyleState.value

    override val caretBounds: Rect?
        get() {
            val current = frame() ?: return null
            return current.caretRect(primaryCaret(), caretWidth).takeIf(current::isVisible)
        }

    override fun boundsOf(offset: Int): Rect? = frame()?.characterRect(offset)
}
