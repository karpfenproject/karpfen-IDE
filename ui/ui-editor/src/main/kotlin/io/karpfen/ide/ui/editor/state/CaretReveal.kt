package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.ui.editor.model.caret.CaretModel

/**
 * The caret reveal after actions and typing (spec §7.2: the caret stays visible with a 3-line margin): sets a
 * [ScrollRequest] for the primary caret. The request is centred when the caret is off-screen by more than one viewport
 * height (a jump: find next, go to definition, document end), and minimal otherwise, so arrow keys and Page Up/Down at
 * the viewport edge scroll by as much as needed instead of re-centring the view. Without known geometry the request is
 * minimal; the view applies it once laid out.
 */
internal class CaretReveal(
    private val text: EditableText,
    private val caret: CaretModel,
    private val viewport: EditorViewport,
    private val requests: ViewRequests,
) {

    /** Requests that the primary caret becomes visible. */
    fun revealPrimary() {
        val offset = caret.primary.position
        val line = text.snapshot.value.lineIndex.lineOf(offset)
        requests.scrollTo(ScrollRequest(offset, center = isJump(line)))
    }

    private fun isJump(line: Int): Boolean {
        val geometry = viewport.state.value
        if (geometry.lineHeight <= 0f || geometry.viewportHeight <= 0f) return false
        val top = geometry.lineTop(line)
        return top + geometry.lineHeight < geometry.scrollY - geometry.viewportHeight ||
            top > geometry.scrollY + 2 * geometry.viewportHeight
    }
}
