package io.karpfen.ide.ui.workbench.statusbar

/** What a [StatusSegment] shows; the view picks its icon by kind. */
internal enum class StatusSegmentKind { ANALYZING, POSITION, SELECTION, LANGUAGE, LINE_SEPARATOR, ENCODING, ERRORS, WARNINGS }

/** One text segment of the status bar, identified by [kind]. */
internal data class StatusSegment(val kind: StatusSegmentKind, val text: String, val tooltip: String? = null) {
    /** A stable id (test tags, item ids). */
    val id: String get() = kind.name.lowercase()
}

/**
 * The texts of the status bar (spec §8), presentation only, so the composable just renders them: the left side shows
 * "Analyzing…" while an analysis runs; the right side `Ln x, Col y`, the selection length (when text is selected),
 * language, line separator and encoding of the active editor, then the workspace's error and warning counts.
 */
internal object StatusBarSegments {

    /** Segments on the left. */
    fun left(state: StatusBarUiState): List<StatusSegment> =
        if (state.isAnalyzing) listOf(StatusSegment(StatusSegmentKind.ANALYZING, "Analyzing…")) else emptyList()

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
