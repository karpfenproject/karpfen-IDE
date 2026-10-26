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
package io.karpfen.ide.ui.workbench.statusbar

import io.karpfen.ide.ui.workbench.output.UnseenOutputProblems

/**
 * What a [StatusSegment] shows; the view picks its icon by kind. [LANGUAGE_TOOLS_ERRORS] and [LANGUAGE_TOOLS_WARNINGS]
 * tell that unseen problems of the language tools are waiting in their output, with or without an error among them.
 */
internal enum class StatusSegmentKind {
    ANALYZING,
    LANGUAGE_TOOLS_ERRORS,
    LANGUAGE_TOOLS_WARNINGS,
    POSITION,
    SELECTION,
    LANGUAGE,
    LINE_SEPARATOR,
    ENCODING,
    ERRORS,
    WARNINGS,
}

/** One text segment of the status bar, identified by [kind]. */
internal data class StatusSegment(val kind: StatusSegmentKind, val text: String, val tooltip: String? = null) {
    /** A stable id (test tags, item ids). */
    val id: String get() = kind.name.lowercase()
}

/**
 * The texts of the status bar, presentation only, so the composable just renders them: the left side shows "Analyzing…"
 * while an analysis runs and the language tools' indicator while errors or warnings of theirs wait unseen in their
 * output; the right side `Ln x, Col y`, the selection length (when text is selected), language, line separator and
 * encoding of the active editor, then the workspace's error and warning counts.
 */
internal object StatusBarSegments {

    /** Segments on the left. */
    fun left(state: StatusBarUiState): List<StatusSegment> = buildList {
        if (state.isAnalyzing) add(StatusSegment(StatusSegmentKind.ANALYZING, "Analyzing…"))
        languageTools(state.unseenOutput)?.let(::add)
    }

    /**
     * The indicator of [unseen] problems of the language tools, or null without any: an error segment if an error is
     * among them, else a warning segment, counting both.
     */
    private fun languageTools(unseen: UnseenOutputProblems): StatusSegment? {
        if (unseen.isEmpty) return null
        val kind = if (unseen.errors > 0) StatusSegmentKind.LANGUAGE_TOOLS_ERRORS else StatusSegmentKind.LANGUAGE_TOOLS_WARNINGS
        val tooltip = "${count(unseen.errors, "error")} and ${count(unseen.warnings, "warning")} of the language tools; " +
            "click to show their output"
        return StatusSegment(kind, "Language tools: ${unseen.errors + unseen.warnings}", tooltip)
    }

    /** [count] followed by [noun], in the plural unless [count] is one. */
    private fun count(count: Long, noun: String): String = if (count == 1L) "1 $noun" else "$count ${noun}s"

    /** Segments on the right. */
    fun right(state: StatusBarUiState): List<StatusSegment> = buildList {
        if (state.line != null && state.column != null) {
            add(StatusSegment(StatusSegmentKind.POSITION, "Ln ${state.line}, Col ${state.column}", "Line and column"))
        }
        if (state.selectionLength > 0) {
            val unit = if (state.selectionLength == 1) "char" else "chars"
            add(StatusSegment(StatusSegmentKind.SELECTION, "${state.selectionLength} $unit", "Selected characters"))
        }
        state.language?.let { add(StatusSegment(StatusSegmentKind.LANGUAGE, it.displayName, "Language")) }
        state.lineSeparator?.let { add(StatusSegment(StatusSegmentKind.LINE_SEPARATOR, it.displayName, "Line separator")) }
        if (state.language != null) add(StatusSegment(StatusSegmentKind.ENCODING, state.encoding, "File encoding"))
        add(StatusSegment(StatusSegmentKind.ERRORS, state.errorCount.toString(), "Errors in the workspace"))
        add(StatusSegment(StatusSegmentKind.WARNINGS, state.warningCount.toString(), "Warnings in the workspace"))
    }
}
