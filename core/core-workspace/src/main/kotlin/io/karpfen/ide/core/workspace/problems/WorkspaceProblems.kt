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
package io.karpfen.ide.core.workspace.problems

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.text.TextPosition
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/**
 * Every diagnostic of every file of a workspace, collected by one rule. The Problems view, the counts in the status
 * bar and `karpfen validate` use it unchanged. They only map and filter it. So the IDE and the command line tool
 * always agree.
 *
 * The rule decides for each file where its diagnostics come from.
 * - For an open document they are the merged diagnostics of its session, `DocumentSession.diagnostics`. These are the
 *   lexer and parser diagnostics and the external sources `"file"` and `"project"`. Sessions of a workspace are
 *   syntax-only, so dsl-tools reads nothing there.
 * - For any other karpfen source of the tree, in a context or loose, they are the `AnalyzedFile.ownDiagnostics` of the
 *   latest analysis plus the project diagnostics of the file from `ProjectAnalysis.diagnosticsFor`.
 * - For any other environment descriptor that the workspace read, they are the diagnostics of its text plus its
 *   project diagnostics. The diagnostics of the text are its syntax errors and loading findings. The project
 *   diagnostics are its findings against the schema and about the files it names.
 * - For any other path with project diagnostics, they are those project diagnostics. These are files and directories
 *   that cannot be read, reported as ERROR `unreadable`.
 *
 * Every case merges with the same function, `mergeDiagnostics` of core-document. Project diagnostics never hold the
 * own diagnostics of a file. Karpfen files get DSL_TOOLS diagnostics only from the project analysis, which reads the
 * context files and never the loose files. So opening or closing a file changes no finding. The syntax errors of a
 * closed descriptor are those the reader found in its text, and the session of an open one computes the same.
 *
 * Files without diagnostics are absent.
 *
 * @property generation the generation of the analysis that the diagnostics of files that are not open come from.
 * @property files one entry per file with diagnostics, sorted by path.
 */
public data class WorkspaceProblems(public val generation: Long, public val files: List<FileProblems>) {

    /** The number of ERROR diagnostics over all files. */
    public val errorCount: Int get() = files.sumOf { it.errorCount }

    /** The number of WARNING diagnostics over all files. */
    public val warningCount: Int get() = files.sumOf { it.warningCount }

    /** The problems of [path], or null if it has none. */
    public fun of(path: Path): FileProblems? = files.firstOrNull { it.path == path }

    public companion object {
        /** No problems. This is the value before the first analysis. */
        public val EMPTY: WorkspaceProblems = WorkspaceProblems(0, emptyList())
    }
}

/**
 * The diagnostics of one file.
 *
 * @property diagnostics the diagnostics. The list is not empty, is sorted with [Diagnostic.ORDER] and has no
 *   duplicates.
 * @property snapshot the `\n`-normalized text the ranges refer to. It is the `VersionedDiagnostics.snapshot` of an open
 *   document, the analyzed snapshot, or the loaded descriptor text. It is null if the text is not available, as for a
 *   file that cannot be read.
 * @property isOpen true if the diagnostics come from the session of an open document. Their ranges then follow the
 *   live text.
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

    /** The number of ERROR diagnostics. */
    public val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }

    /** The number of WARNING diagnostics. */
    public val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }

    /**
     * The zero-based position of the start of the range of [diagnostic] in [snapshot], clamped to the text. Without a
     * snapshot it is [TextPosition.ORIGIN]. The UI and the command line tool show it one-based.
     */
    public fun positionOf(diagnostic: Diagnostic): TextPosition =
        snapshot?.let { it.positionOf(diagnostic.range.start.coerceAtMost(it.length)) } ?: TextPosition.ORIGIN
}
