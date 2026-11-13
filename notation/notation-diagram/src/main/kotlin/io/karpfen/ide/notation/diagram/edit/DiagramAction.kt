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
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout

/**
 * A change a user makes to a model through its diagram. The text of the model is the only thing that is written, so
 * the change is a list of edits of that text. The diagram follows from the new text.
 *
 * @property edits the edits of the text, all against the text the diagram was derived from. They do not overlap.
 * @property arranges true if the change places something by hand. The layout on screen is then fixed first, as by
 *   any other change by hand, so the new element appears where the user asked for it and nothing else moves. A change
 *   that does not arrange leaves an automatic layout automatic.
 * @property layout what the change means for the layout by hand, or null if it means nothing. It places a new element
 *   where the user asked for it, and it lets a renamed element keep its place.
 */
public class DiagramChange(
    public val edits: List<TextEdit>,
    public val arranges: Boolean = false,
    public val layout: ((VisLayout) -> VisLayout)? = null,
)

/**
 * Something a user can do at a point of a diagram. The view shows the actions of a point as the entries of a context
 * menu. Some actions need more from the user before they change anything: a click on another element or a text. Such
 * an action leads to the next one, and the last one of the chain is a [Change]. An [Open] shows a piece of the model
 * as text, so whatever the menu has no entry for can still be done in the diagram.
 */
public sealed interface DiagramAction {
    /** What the entry of the menu says. */
    public val label: String

    /**
     * Changes the model at once.
     *
     * @property change makes the change, or gives null if there is nothing to change after all.
     */
    public class Change(override val label: String, public val change: () -> DiagramChange?) : DiagramAction

    /**
     * Needs the user to click a box of the diagram.
     *
     * @property prompt what the view tells the user to click.
     * @property candidates the ids of the boxes the user may click. The view marks them.
     * @property next the action that follows for the box the user clicked, or null if nothing follows.
     */
    public class Pick(
        override val label: String,
        public val prompt: String,
        public val candidates: Set<String>,
        public val next: (String) -> DiagramAction?,
    ) : DiagramAction

    /**
     * Opens a piece of the model as text, to be edited in a code cell.
     *
     * @property cell the cell to open.
     */
    public class Open(override val label: String, public val cell: DiagramCell) : DiagramAction

    /**
     * Needs the user to type a short text, such as a name.
     *
     * @property initial the text the field starts with.
     * @property next the action that follows for the text the user typed, or null if nothing follows.
     */
    public class Ask(
        override val label: String,
        public val initial: String,
        public val next: (String) -> DiagramAction?,
    ) : DiagramAction
}

/**
 * Tells what a user can do at a point of one kind of diagram.
 *
 * @param D the diagram, as it is derived from the text.
 * @param L the layout of that kind of diagram.
 */
public fun interface DiagramActions<D, L : DiagramLayout> {
    /**
     * The actions at [point] of [diagram], which is drawn as [layout] and was derived from the text [source]. The list
     * is empty where there is nothing to do.
     */
    public fun at(source: String, diagram: D, layout: L, point: DiagramPoint): List<DiagramAction>

    public companion object {
        /** No actions anywhere. */
        public fun <D, L : DiagramLayout> none(): DiagramActions<D, L> = DiagramActions { _, _, _, _ -> emptyList() }
    }
}
