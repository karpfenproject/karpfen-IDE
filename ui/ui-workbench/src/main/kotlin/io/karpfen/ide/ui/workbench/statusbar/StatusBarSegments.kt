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
import io.karpfen.ide.ui.workbench.settings.Zoom
import kotlin.math.roundToInt

/** What a [StatusSegment] shows. The view picks the segment's icon and click action by its kind. */
internal enum class StatusSegmentKind {
    /** The label of the Problems tool window while the workspace has no error and no warning. */
    PROBLEMS,

    /** The label of the Problems tool window while the workspace has at least one error. */
    PROBLEMS_ERRORS,

    /** The label of the Problems tool window while the workspace has warnings and no error. */
    PROBLEMS_WARNINGS,

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

    /** Makes the text of the editors smaller. */
    EDITOR_ZOOM_OUT,

    /** The font size of the editors. A click sets it back to the normal size. */
    EDITOR_ZOOM,

    /** Makes the text of the editors larger. */
    EDITOR_ZOOM_IN,

    /** Makes the whole workbench smaller. */
    UI_ZOOM_OUT,

    /** The scale of the whole workbench. A click sets it back to the normal size. */
    UI_ZOOM,

    /** Makes the whole workbench larger. */
    UI_ZOOM_IN,
}

/** One text segment of the status bar, identified by [kind]. */
internal data class StatusSegment(val kind: StatusSegmentKind, val text: String, val tooltip: String? = null) {
    /** A stable id, used for test tags and item ids. */
    val id: String get() = kind.name.lowercase()
}

/**
 * The texts of the status bar. This is presentation only, so the composable just renders them.
 *
 * The left side starts with the label of the Problems tool window. The tool window is closed while there is nothing
 * to show, and the label then is all that is left of it. Without problems the label is greyed out and does nothing,
 * since there is nothing to open. It carries the error icon and the number of problems while
 * the workspace has errors, and the warning icon while it has only warnings, so the user sees that there is something
 * to open. The left side also shows "Analyzing…" while an analysis runs. It shows the runtime's status in a few words
 * while a workspace is open. And it shows the language tools' indicator while errors or warnings of theirs wait unseen
 * in their output.
 *
 * The right side shows `Ln x, Col y` and the selection length when text is selected. Then come the language, the line
 * separator and the encoding of the active editor. The workspace's error and warning counts come last.
 *
 * The zoom controls of [zoom] follow at the right end, a minus button, the current scale of the workbench and a plus
 * button. The controls of [editorZoom] look the same and size the text of the editors. They are not part of the status
 * bar. The editor area shows them in a bar of its own below the editor.
 */
internal object StatusBarSegments {

    /** The tooltip of the runtime's status. */
    private const val RUNTIME_TOOLTIP = "Click to show the Runtime tool window"

    /** The tooltip of the Problems label while there are problems. */
    private const val PROBLEMS_TOOLTIP = "Click to show or hide the Problems tool window"

    /** The tooltip of the Problems label while there is no problem. */
    private const val NO_PROBLEMS_TOOLTIP = "No problems to show"

    /** The segments on the left. */
    fun left(state: StatusBarUiState): List<StatusSegment> = buildList {
        add(problems(state))
        if (state.isAnalyzing) add(StatusSegment(StatusSegmentKind.ANALYZING, "Analyzing…"))
        state.runtime?.let { add(StatusSegment(StatusSegmentKind.RUNTIME, RuntimeTexts.hostSummary(it), RUNTIME_TOOLTIP)) }
        languageTools(state.unseenOutput)?.let(::add)
    }

    /**
     * The label of the Problems tool window. With errors or warnings in the workspace it names their number and gets
     * the kind that carries the icon of the worst severity.
     */
    private fun problems(state: StatusBarUiState): StatusSegment {
        val total = state.errorCount + state.warningCount
        val kind = when {
            state.errorCount > 0 -> StatusSegmentKind.PROBLEMS_ERRORS
            state.warningCount > 0 -> StatusSegmentKind.PROBLEMS_WARNINGS
            else -> StatusSegmentKind.PROBLEMS
        }
        return if (total > 0) StatusSegment(kind, "Problems: $total", PROBLEMS_TOOLTIP) else StatusSegment(kind, "Problems", NO_PROBLEMS_TOOLTIP)
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

    /**
     * The zoom controls for the scale [uiScale] of the workbench: a minus button, the current scale and a plus button.
     * A click on the scale sets it back to normal.
     */
    fun zoom(uiScale: Float): List<StatusSegment> = listOf(
        StatusSegment(StatusSegmentKind.UI_ZOOM_OUT, "-", "Make everything smaller"),
        StatusSegment(StatusSegmentKind.UI_ZOOM, "Zoom ${Zoom.percent(uiScale)}", "Size of the whole window. Click for the normal size"),
        StatusSegment(StatusSegmentKind.UI_ZOOM_IN, "+", "Make everything larger"),
    )

    /**
     * The zoom controls for the font size [editorFontSize] of the editors: a minus button, the current size and a
     * plus button. A click on the size sets it back to normal.
     */
    fun editorZoom(editorFontSize: Float): List<StatusSegment> = listOf(
        StatusSegment(StatusSegmentKind.EDITOR_ZOOM_OUT, "-", "Make the text of the editor smaller"),
        StatusSegment(StatusSegmentKind.EDITOR_ZOOM, "Font size ${editorFontSize.roundToInt()}", "Font size of the editor. Click for the normal size"),
        StatusSegment(StatusSegmentKind.EDITOR_ZOOM_IN, "+", "Make the text of the editor larger"),
    )

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
