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

/** Test tags of the editor area, [EditorArea]. The tabs themselves carry the tags of `TabStripTestTags`. */
public object EditorAreaTestTags {
    /** The editor area as a whole, with the tab strip, the banner and the editor. */
    public const val AREA: String = "Workbench.editorArea"

    /** The `CodeEditor` of the active tab. */
    public const val EDITOR: String = "Workbench.editor"

    /** The bar below the editor, with the zoom controls of the editor's font size. */
    public const val ZOOM_BAR: String = "Workbench.editorZoomBar"

    /** The statechart next to or in place of the text. */
    public const val DIAGRAM: String = "Workbench.editorDiagram"

    /** The button of the view mode named [label], which is "Text", "Split" or "Diagram". */
    public fun viewMode(label: String): String = "Workbench.viewMode:$label"

    /** The conflict banner of the active tab. */
    public const val CONFLICT_BANNER: String = "Workbench.conflictBanner"

    /** A button of the conflict banner, by its label, which is "Reload", "Close" or "Keep mine". */
    public fun conflictButton(label: String): String = "Workbench.conflictBanner.button:$label"
}
