package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextPosition
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * Every diagnostic of every file of a workspace, under one rule. The Problems view, the status-bar counts and
 * `karpfen validate` consume it unchanged (they only map and filter), so the IDE and the CLI always agree.
 *
 * The rule, per file:
 * - an open document: its session's merged diagnostics (`DocumentSession.diagnostics`: lexer, parser and the external
 *   sources `"file"` and `"project"`; sessions of a workspace are syntax-only, so no standalone conversion);
 * - any other karpfen source of the tree, in a context or loose: `AnalyzedFile.ownDiagnostics` of the latest analysis
 *   plus the file's project diagnostics (`ProjectAnalysis.diagnosticsFor`);
 * - any other environment descriptor the workspace read: the diagnostics of its text (syntax errors, loading findings)
 *   plus its project diagnostics (its findings against the schema and about the files it names);
 * - any other path with project diagnostics (files and directories that cannot be read, as ERROR `unreadable`): those.
 *
 * Every case merges with the same function (`mergeDiagnostics` of core-document), and project diagnostics never hold
 * a file's own diagnostics; karpfen files get DSL_TOOLS diagnostics only from the project analysis (context files
 * converted, loose files never). So opening or closing a file changes no finding: a closed descriptor's syntax errors
 * are those the reader found in its text, an open one's session computes the same.
 *
 * Files without diagnostics are absent.
 *
 * @property generation the generation of the analysis the diagnostics of unopened files come from.
 * @property files one entry per file with diagnostics, sorted by path.
 */
public data class WorkspaceProblems(public val generation: Long, public val files: List<FileProblems>) {

    /** Number of ERROR diagnostics over all files. */
    public val errorCount: Int get() = files.sumOf { it.errorCount }

    /** Number of WARNING diagnostics over all files. */
    public val warningCount: Int get() = files.sumOf { it.warningCount }

    /** The problems of [path], or null if it has none. */
    public fun of(path: Path): FileProblems? = files.firstOrNull { it.path == path }

    public companion object {
        /** No problems (before the first analysis). */
        public val EMPTY: WorkspaceProblems = WorkspaceProblems(0, emptyList())
    }
}

/**
 * The diagnostics of one file.
 *
 * @property diagnostics non-empty, sorted with [Diagnostic.ORDER], without duplicates.
 * @property snapshot the `\n`-normalized text the ranges refer to: the open document's `VersionedDiagnostics.snapshot`,
 *   the analyzed snapshot, or the loaded descriptor text; null if the text is unavailable (unreadable file).
 * @property isOpen true if the diagnostics come from an open document's session (their ranges follow the live text).
 */
public data class FileProblems(
    public val path: Path,
    public val kind: FileKind,
    public val diagnostics: List<Diagnostic>,
    public val snapshot: TextSnapshot?,
    public val isOpen: Boolean,
) {
    init {
        require(diagnostics.isNotEmpty()) { "files without diagnostics are not listed" }
    }

    /** Number of ERROR diagnostics. */
    public val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }

    /** Number of WARNING diagnostics. */
    public val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    /**
     * Zero-based position of the start of [diagnostic]'s range in [snapshot] (clamped to the text);
     * [TextPosition.ORIGIN] without a snapshot. The UI and the CLI show it one-based.
     */
    public fun positionOf(diagnostic: Diagnostic): TextPosition =
        snapshot?.let { it.positionOf(diagnostic.range.start.coerceAtMost(it.length)) } ?: TextPosition.ORIGIN
}
