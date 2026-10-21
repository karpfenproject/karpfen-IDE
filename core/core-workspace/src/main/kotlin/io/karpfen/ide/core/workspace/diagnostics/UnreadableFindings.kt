package io.karpfen.ide.core.workspace.diagnostics

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextRange

/**
 * The ERROR [WorkspaceDiagnosticCodes.UNREADABLE] of everything the workspace cannot read, so a file the analysis could
 * not load, a descriptor and a directory the scan could not list read alike. At the start of the file: without its
 * text there is no other position.
 */
internal object UnreadableFindings {

    /** A file that cannot be read, for the given [reason] (e.g. "access denied"). */
    fun file(reason: String): Diagnostic = error("Cannot read this file ($reason), so it was not analyzed.")

    /** A directory that cannot be listed. */
    fun directory(): Diagnostic = error("Cannot read this directory, so its files were not analyzed.")

    private fun error(message: String): Diagnostic =
        Diagnostic(TextRange.EMPTY, Severity.ERROR, message, WorkspaceDiagnosticCodes.UNREADABLE, DiagnosticSource.IDE)
}
