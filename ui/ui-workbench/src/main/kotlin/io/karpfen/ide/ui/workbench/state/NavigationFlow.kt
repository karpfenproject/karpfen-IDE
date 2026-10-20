package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.state.EditorHandle
import io.karpfen.ide.ui.workbench.tabs.EditorTabsState

/**
 * `navigateTo` (spec §8): opens the location's file ([FileOpenFlow], so files the editor cannot open go to the default
 * application) and moves the caret to the range start, centred and focused. The range is mapped into the document's
 * current text with `EditableText.mapRange` — the one range-mapping rule (decisions 26 and 35 of the work-package
 * plan) — and clamped to the text when that answers null (another lineage, a newer version, evicted history, or no
 * version at all). Caret, scroll and focus requests are pending editor state, so they work for a tab not composed yet.
 */
internal class NavigationFlow(private val files: FileOpenFlow, private val tabs: EditorTabsState) {

    suspend fun navigateTo(location: Location) {
        val path = location.path.toAbsolutePath().normalize()
        if (!files.openFile(path)) return
        val editor = tabs.editorFor(path) ?: return
        val offset = targetRange(editor, location).start
        editor.caret.moveTo(offset)
        editor.scrollTo(offset, center = true)
        editor.requestFocus()
    }

    private fun targetRange(editor: EditorHandle, location: Location): TextRange {
        val text = editor.session.text
        val version = location.version
        val lineage = location.lineage
        val mapped = if (version != null && lineage != null) text.mapRange(location.range, version, lineage) else null
        return mapped ?: clamp(location.range, text.snapshot.value.length)
    }

    private fun clamp(range: TextRange, length: Int): TextRange {
        val start = range.start.coerceIn(0, length)
        return TextRange(start, range.end.coerceIn(start, length))
    }
}
