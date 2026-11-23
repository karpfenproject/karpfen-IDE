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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.state.EditorHandle
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import io.karpfen.ide.ui.workbench.tabs.PaneId

/**
 * The flow behind `navigateTo`. It opens the file of the location through [FileOpenFlow], so files the editor cannot
 * open go to the default application. Then it moves the caret to the start of the range, scrolls it to the centre and
 * focuses the editor.
 *
 * The range is mapped into the document's current text with `EditableText.mapRange`. That is the single rule for
 * mapping ranges between versions. Nobody maps the two bounds of a range separately. When the mapping returns null, the
 * range is clamped to the text instead. That happens for another lineage, a newer version, history that was evicted,
 * or a location without a version.
 *
 * Versions count per text, and a reopened document starts again at version 0. That is why a [Location] carries the
 * lineage of its text next to the version. Caret, scroll and focus requests are pending editor state, so they also work
 * for a tab that is not composed yet.
 */
internal class NavigationFlow(private val files: FileOpenFlow, private val tabs: EditorTabsState) {

    /**
     * Navigates to [location] in the pane of [editor], which asked for it, as [navigateTo] does. So a definition
     * lookup that answers after the focus moved to another pane still opens where it was asked for. If [editor] has no
     * tab any more, the active pane is used.
     */
    suspend fun navigateFrom(editor: EditorHandle, location: Location) {
        navigateTo(location, tabs.paneOf(editor))
    }

    /**
     * Opens the file of [location] in the pane [pane], or in the active pane if [pane] is null or gone, and puts the
     * caret of that pane's editor at the start of the mapped range. The pane becomes the active one. The caret is
     * scrolled to the centre and the editor gets the focus. A pane that shows the file's diagram alone switches to
     * the text and the diagram next to each other, so the place shows.
     */
    suspend fun navigateTo(location: Location, pane: PaneId? = null) {
        val path = location.path.toAbsolutePath().normalize()
        if (!files.openFile(path, pane)) return
        val shown = tabs.activePane.value
        val editor = tabs.editorFor(shown, path) ?: return
        if (tabs.panes.value[shown]?.viewModes?.get(path) == EditorViewMode.DIAGRAM) tabs.setViewMode(shown, path, EditorViewMode.SPLIT)
        val offset = targetRange(editor, location).start
        editor.caret.moveTo(offset)
        editor.scrollTo(offset, center = true)
        editor.requestFocus()
    }

    /**
     * The range of [location] in the current text of [editor]. It is mapped from the location's version and lineage.
     * If that is not possible, it is clamped to the text.
     */
    private fun targetRange(editor: EditorHandle, location: Location): TextRange {
        val text = editor.session.text
        val version = location.version
        val lineage = location.lineage
        val mapped = if (version != null && lineage != null) text.mapRange(location.range, version, lineage) else null
        return mapped ?: clamp(location.range, text.snapshot.value.length)
    }

    /** Cuts [range] to a text of [length]. The result is never inverted. */
    private fun clamp(range: TextRange, length: Int): TextRange {
        val start = range.start.coerceIn(0, length)
        return TextRange(start, range.end.coerceIn(start, length))
    }
}
