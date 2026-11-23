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
package io.karpfen.ide.ui.workbench.tabs.view

/**
 * Test tags of the editor area: the tree of panes, [EditorPaneTree], and the area of each pane, [EditorArea]. The tabs
 * themselves carry the tags of `TabStripTestTags`.
 *
 * Each pane carries its own tag, [pane], and each split its own, [split]. The parts of a pane, such as its editor, its
 * diagram and its buttons, carry the same tags in every pane. With one pane they are unique. With several panes a test
 * finds the part of one pane below that pane's tag, for example with `hasTestTag(EDITOR) and
 * hasAnyAncestor(hasTestTag(pane(id)))`.
 */
public object EditorAreaTestTags {
    /** The editor area as a whole, with all its panes. */
    public const val AREA: String = "Workbench.editorArea"

    /** The area of the pane with the id [value], with its tab strip, its banner, its editor and its bar. */
    public fun pane(value: Int): String = "Workbench.editorPane:$value"

    /** The split with the id [value], with its two parts and the border between them. */
    public fun split(value: Int): String = "Workbench.editorSplit:$value"

    /** The button of a pane's tab strip that splits the pane towards the side named [side], such as `RIGHT`. */
    public fun splitButton(side: String): String = "Workbench.splitPane:$side"

    /** The `CodeEditor` of the active tab of a pane. */
    public const val EDITOR: String = "Workbench.editor"

    /** The bar below the editor, with the zoom controls of the editor's font size. */
    public const val ZOOM_BAR: String = "Workbench.editorZoomBar"

    /** The statechart next to or in place of the text. */
    public const val DIAGRAM: String = "Workbench.editorDiagram"

    /** The button of the view mode named [label], which is "Text", "Split" or "Diagram". */
    public fun viewMode(label: String): String = "Workbench.viewMode:$label"

    /**
     * The button below the editor that shows the states of the instance on the object with [key] in the diagram, in
     * the diagram of a fragment those of the copy that the container state [page] of the instance uses.
     */
    public fun liveInstance(key: String, page: String? = null): String = "Workbench.liveInstance:$key" + (page?.let { "@$it" } ?: "")

    /** The bar above the diagram of a fragment that names the files using it. */
    public const val PAGE_USERS: String = "Workbench.pageUsers"

    /** The link of the bar above the diagram of a fragment that opens the file named [fileName]. */
    public fun pageUser(fileName: String): String = "Workbench.pageUser:$fileName"

    /** The button below the editor that validates the whole project. */
    public const val VALIDATE: String = "Workbench.validate"

    /** The conflict banner of the active tab. */
    public const val CONFLICT_BANNER: String = "Workbench.conflictBanner"

    /** A button of the conflict banner, by its label, which is "Reload", "Close" or "Keep mine". */
    public fun conflictButton(label: String): String = "Workbench.conflictBanner.button:$label"
}
