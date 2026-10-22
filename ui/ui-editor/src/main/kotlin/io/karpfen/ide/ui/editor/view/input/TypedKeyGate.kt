package io.karpfen.ide.ui.editor.view.input

/**
 * Keeps a key press the editor handled from also typing its character: a platform sends a "typed" event after the
 * key-down of a printable key (Ctrl+Space, the second completion key on Windows, types a space); when the editor
 * consumed the key-down, the typed event that follows it is swallowed as well. Every key-down decides afresh, so the
 * gate never swallows a character of an unhandled press, and input-method commits (which are no key events) always
 * pass. Used from the UI thread.
 */
internal class TypedKeyGate {

    private var swallowNextTyped = false

    /** A key-down was routed to the editor; [handled] says whether the editor consumed it. */
    fun keyDown(handled: Boolean) {
        swallowNextTyped = handled
    }

    /** A typed event arrived; returns true if it belongs to a handled key-down and must be consumed. */
    fun typed(): Boolean {
        val swallow = swallowNextTyped
        swallowNextTyped = false
        return swallow
    }
}
