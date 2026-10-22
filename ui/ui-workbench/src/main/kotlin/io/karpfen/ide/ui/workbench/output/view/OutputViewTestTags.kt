package io.karpfen.ide.ui.workbench.output.view

/** Test tags of the Language Tools Output view ([OutputView]). */
public object OutputViewTestTags {
    /** The list of lines. */
    public const val LINES: String = "Workbench.output.lines"

    /** The toggle that keeps the newest line in sight. */
    public const val FOLLOW: String = "Workbench.output.follow"

    /** The button that clears the output. */
    public const val CLEAR: String = "Workbench.output.clear"

    /** The line with the sequence number [sequence]. */
    public fun line(sequence: Long): String = "Workbench.output.line:$sequence"
}
