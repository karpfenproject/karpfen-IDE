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
package io.karpfen.ide.core.document

import io.karpfen.ide.core.text.TextRange

/**
 * A problem reported for a range of a document. Library code never lets an exception from parsing or analysis escape
 * to the UI; it turns every such failure into a diagnostic. Only programming errors (violated preconditions) throw.
 *
 * @property range the affected range in the coordinates of the document version the diagnostic was computed for; may
 *   be empty (e.g. "missing `}`" at end of file).
 * @property severity how serious the problem is.
 * @property message the text shown to the user.
 * @property code a stable machine-readable code, see [DiagnosticCodes].
 * @property source the stage that reported the problem.
 */
public data class Diagnostic(
    public val range: TextRange,
    public val severity: Severity,
    public val message: String,
    public val code: String,
    public val source: DiagnosticSource,
) {
    public companion object {
        /**
         * Document order: by range start, then range end, then severity (most severe first), source, code and message.
         * Lists of diagnostics handed out by the core modules are sorted with this comparator.
         */
        public val ORDER: Comparator<Diagnostic> =
            compareBy<Diagnostic> { it.range.start }
                .thenBy { it.range.end }
                .thenBy { it.severity }
                .thenBy { it.source }
                .thenBy { it.code }
                .thenBy { it.message }
    }
}

/** Severity of a [Diagnostic]; declaration order is from most to least severe. */
public enum class Severity {
    /** The file is invalid (red). */
    ERROR,

    /** Probably a mistake (yellow). */
    WARNING,

    /** Noteworthy, not a problem (blue). */
    INFO,

    /** A suggestion; not shown in the Problems view by default. */
    HINT,
}

/** Which stage produced a [Diagnostic]. */
public enum class DiagnosticSource {
    /**
     * The lexer of the document's language: the dsl-tools ANTLR lexer, run with an IDE error listener, for the modeling
     * languages, and the IDE's own JSON lexer for environment descriptors.
     */
    LEXER,

    /**
     * The parser of the document's language: the dsl-tools ANTLR parser, run with an IDE error listener, for the
     * modeling languages, and the IDE's own JSON tree builder for environment descriptors.
     */
    PARSER,

    /**
     * A dsl-tools reader (semantic model construction): a finding at the place the reader reports, whose code is the
     * stable id of the dsl-tools diagnostic code, such as `kmodel.unknown-property` or `kstates.initial-state-count`.
     */
    DSL_TOOLS,

    /**
     * The IDE itself (EVAL warnings here; file encoding, line separators, environment descriptors and modeling contexts
     * in core-workspace).
     */
    IDE,
}

/**
 * Stable diagnostic codes ([Diagnostic.code]) produced by core-document: lexer, parser, EVAL scanning and internal
 * failures. The findings of the dsl-tools readers ([DiagnosticSource.DSL_TOOLS]) carry the stable ids of their
 * dsl-tools codes instead, so a new kind of finding in dsl-tools needs no constant here. Codes of findings that only
 * core-workspace produces (file loading, environment descriptors, modeling contexts) are `WorkspaceDiagnosticCodes` of
 * core-workspace, so a new workspace finding never edits this file.
 */
public object DiagnosticCodes {
    /** Lexer error (unrecognized input); source [DiagnosticSource.LEXER]. */
    public const val LEXER_ERROR: String = "lexer-error"

    /** Parser error (unexpected or missing token); source [DiagnosticSource.PARSER]. */
    public const val SYNTAX_ERROR: String = "syntax-error"

    /**
     * `//` inside an EVAL body starts a karpfen comment, so the rest of the line never reaches Python (a WARNING,
     * source [DiagnosticSource.IDE]).
     */
    public const val EVAL_DOUBLE_SLASH: String = "eval-double-slash"

    /** An unexpected internal failure that was caught and reported instead of crashing (ERROR). */
    public const val INTERNAL_ERROR: String = "internal-error"
}
