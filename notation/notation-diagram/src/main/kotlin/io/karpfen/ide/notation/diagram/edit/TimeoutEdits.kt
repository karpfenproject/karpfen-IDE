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
package io.karpfen.ide.notation.diagram.edit

import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.notation.diagram.statechart.StatechartDiagram
import io.karpfen.ide.notation.diagram.text.TextBlocks

/**
 * The changes of the timeout of a state that its line in a statechart offers, each as an edit of the text. A timeout
 * stands in the tick limit of its state, `TICK LIMIT { TIMEOUT: AFTER 10 TICKS TRANSITION TO "state" }`. Every
 * function takes the text and the diagram that was derived from exactly that text, and gives the change or null if
 * there is nothing to change.
 */
public object TimeoutEdits {

    /** Lets the timeout of the state [state] lead to the state [target]. Only the name of its target changes. */
    public fun retarget(text: String, diagram: StatechartDiagram, state: String, target: String): DiagramChange? {
        val timeout = diagram.state(state)?.timeout ?: return null
        if (diagram.state(target) == null || timeout.target == target) return null
        return DiagramChange(listOf(TextEdit(timeout.targetRange, target)))
    }

    /**
     * Takes the timeout out of the tick limit of the state [state]. If the tick limit has no delay either, the whole
     * tick limit goes, because it would limit nothing any more.
     */
    public fun remove(text: String, diagram: StatechartDiagram, state: String): DiagramChange? {
        val node = diagram.state(state) ?: return null
        val timeout = node.timeout ?: return null
        val limit = node.tickLimitRange ?: return null
        return DiagramChange(listOf(TextBlocks.removal(text, if (node.delay == null) limit else timeout.range)))
    }
}
