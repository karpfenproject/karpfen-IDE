package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Calls [onLost] when this element or one of its children had keyboard focus and none has it any more (focus moved to
 * another component). Nothing happens before the element first gained focus.
 */
internal fun Modifier.onFocusLost(onLost: () -> Unit): Modifier = this then FocusLossElement(onLost)

/** The focus history that decides "lost": true once after a focused state is followed by an unfocused one. */
internal class FocusLossTracker {
    private var hadFocus = false

    /** Records [hasFocus]; returns true if focus was just lost. */
    fun update(hasFocus: Boolean): Boolean {
        val lost = hadFocus && !hasFocus
        hadFocus = hasFocus
        return lost
    }
}

private data class FocusLossElement(val onLost: () -> Unit) : ModifierNodeElement<FocusLossNode>() {
    override fun create(): FocusLossNode = FocusLossNode(onLost)

    override fun update(node: FocusLossNode) {
        node.onLost = onLost
    }
}

private class FocusLossNode(var onLost: () -> Unit) : Modifier.Node(), FocusEventModifierNode {
    private val tracker = FocusLossTracker()

    override fun onFocusEvent(focusState: FocusState) {
        if (tracker.update(focusState.hasFocus)) onLost()
    }
}
