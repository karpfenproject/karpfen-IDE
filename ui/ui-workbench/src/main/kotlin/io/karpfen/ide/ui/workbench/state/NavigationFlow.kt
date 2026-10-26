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

/**
 * `navigateTo`: opens the location's file ([FileOpenFlow], so files the editor cannot open go to the default
 * application) and moves the caret to the range start, centred and focused. The range is mapped into the document's
 * current text with `EditableText.mapRange`, the one rule for mapping ranges between versions (nobody maps the two
 * bounds of a range separately), and clamped to the text when that answers null (another lineage, a newer version,
 * evicted history, or no version at all). Versions count per text and a reopened document starts again at version 0,
 * which is why a [Location] carries its text's lineage next to the version. Caret, scroll and focus requests are
 * pending editor state, so they work for a tab not composed yet.
 */
internal class NavigationFlow(private val files: FileOpenFlow, private val tabs: EditorTabsState) {

    /** Opens the file of [location] and puts the caret at the start of its mapped range, centred and focused. */
    suspend fun navigateTo(location: Location) {
        val path = location.path.toAbsolutePath().normalize()
        if (!files.openFile(path)) return
        val editor = tabs.editorFor(path) ?: return
        val offset = targetRange(editor, location).start
        editor.caret.moveTo(offset)
        editor.scrollTo(offset, center = true)
        editor.requestFocus()
    }

    /** The range of [location] in [editor]'s current text: mapped from its version and lineage, else clamped. */
    private fun targetRange(editor: EditorHandle, location: Location): TextRange {
        val text = editor.session.text
        val version = location.version
        val lineage = location.lineage
        val mapped = if (version != null && lineage != null) text.mapRange(location.range, version, lineage) else null
        return mapped ?: clamp(location.range, text.snapshot.value.length)
    }

    /** [range] cut to a text of [length], never inverted. */
    private fun clamp(range: TextRange, length: Int): TextRange {
        val start = range.start.coerceIn(0, length)
        return TextRange(start, range.end.coerceIn(start, length))
    }
}
