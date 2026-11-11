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
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts

/** What a [StatusSegment] shows. The view picks the segment's icon and click action by its kind. */
internal enum class StatusSegmentKind {
    /** "Analyzing…" while an analysis of the workspace runs. */
    ANALYZING,

    /** What the runtime of the workspace is doing. */
    RUNTIME,

    /** Unseen problems of the language tools wait in their output, and at least one of them is an error. */
    LANGUAGE_TOOLS_ERRORS,

    /** Unseen problems of the language tools wait in their output, and none of them is an error. */
    LANGUAGE_TOOLS_WARNINGS,

    /** The caret's line and column. */
    POSITION,

    /** The length of the selection. */
    SELECTION,

    /** The language of the active editor. */
    LANGUAGE,

    /** The line separator of the active document. */
    LINE_SEPARATOR,

    /** The encoding of the active document. */
    ENCODING,

    /** The number of errors in the workspace. */
    ERRORS,

    /** The number of warnings in the workspace. */
    WARNINGS,
}

/** One text segment of the status bar, identified by [kind]. */
internal data class StatusSegment(val kind: StatusSegmentKind, val text: String, val tooltip: String? = null) {
    /** A stable id, used for test tags and item ids. */
    val id: String get() = kind.name.lowercase()
}

/**
 * The texts of the status bar. This is presentation only, so the composable just renders them.
 *
 * The left side shows "Analyzing…" while an analysis runs. It shows the runtime's status in a few words while a
 * workspace is open. And it shows the language tools' indicator while errors or warnings of theirs wait unseen in their
 * output.
 *
 * The right side shows `Ln x, Col y` and the selection length when text is selected. Then come the language, the line
 * separator and the encoding of the active editor. The workspace's error and warning counts come last.
 */
internal object StatusBarSegments {

    /** The tooltip of the runtime's status. */
    private const val RUNTIME_TOOLTIP = "Click to show the Runtime tool window"

    /** The segments on the left. */
    fun left(state: StatusBarUiState): List<StatusSegment> = buildList {
        if (state.isAnalyzing) add(StatusSegment(StatusSegmentKind.ANALYZING, "Analyzing…"))
        state.runtime?.let { add(StatusSegment(StatusSegmentKind.RUNTIME, RuntimeTexts.hostSummary(it), RUNTIME_TOOLTIP)) }
        languageTools(state.unseenOutput)?.let(::add)
    }

    /**
     * The indicator of [unseen] problems of the language tools, or null if there are none. It is an error segment if
     * an error is among them, and a warning segment otherwise. Its count includes both errors and warnings.
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

    /** The segments on the right. */
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
