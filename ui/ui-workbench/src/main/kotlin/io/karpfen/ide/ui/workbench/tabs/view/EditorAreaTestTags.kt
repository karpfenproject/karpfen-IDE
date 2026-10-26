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

/** Test tags of the editor area ([EditorArea]); the tabs themselves carry `TabStripTestTags`. */
public object EditorAreaTestTags {
    /** The editor area (tab strip, banner and editor). */
    public const val AREA: String = "Workbench.editorArea"

    /** The `CodeEditor` of the active tab. */
    public const val EDITOR: String = "Workbench.editor"

    /** The external-change banner of the active tab. */
    public const val CONFLICT_BANNER: String = "Workbench.conflictBanner"

    /** A button of the external-change banner, by its label ("Reload", "Close", "Keep mine"). */
    public fun conflictButton(label: String): String = "Workbench.conflictBanner.button:$label"
}
