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

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Calls [onLost] when this element or one of its children had keyboard focus and none of them has it any more, because
 * focus moved to another component. Nothing happens before the element first gained focus.
 */
internal fun Modifier.onFocusLost(onLost: () -> Unit): Modifier = this then FocusLossElement(onLost)

/** The focus history that decides when focus was lost. That is the case once a focused state is followed by an unfocused one. */
internal class FocusLossTracker {
    private var hadFocus = false

    /** Records [hasFocus] and returns true if focus was just lost. */
    fun update(hasFocus: Boolean): Boolean {
        val lost = hadFocus && !hasFocus
        hadFocus = hasFocus
        return lost
    }
}

/** The modifier element of [onFocusLost]. An update hands the new callback to the existing node. */
private data class FocusLossElement(val onLost: () -> Unit) : ModifierNodeElement<FocusLossNode>() {
    override fun create(): FocusLossNode = FocusLossNode(onLost)

    override fun update(node: FocusLossNode) {
        node.onLost = onLost
    }
}

/** The node of [onFocusLost]. It feeds every focus event into a [FocusLossTracker] and calls [onLost] when focus is lost. */
private class FocusLossNode(var onLost: () -> Unit) : Modifier.Node(), FocusEventModifierNode {
    private val tracker = FocusLossTracker()

    override fun onFocusEvent(focusState: FocusState) {
        if (tracker.update(focusState.hasFocus)) onLost()
    }
}
