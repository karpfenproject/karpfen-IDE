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
 * to the UI. It turns every such failure into a diagnostic. Only programming errors, that is violated preconditions,
 * throw.
 *
 * @property range the affected range in the coordinates of the document version the diagnostic was computed for. It
 *   may be empty, for example for a "missing `}`" at the end of the file.
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
         * Document order. Diagnostics are ordered by range start, then by range end, then by severity with the most
         * severe first, and then by source, code and message. Lists of diagnostics handed out by the core modules are
         * sorted with this comparator.
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

/** Severity of a [Diagnostic]. The entries are declared from most to least severe. */
public enum class Severity {
    /** The file is invalid. Shown in red. */
    ERROR,

    /** Probably a mistake. Shown in yellow. */
    WARNING,

    /** Noteworthy, but not a problem. Shown in blue. */
    INFO,

    /** A suggestion. The Problems view does not show it by default. */
    HINT,
}

/** Which stage produced a [Diagnostic]. */
public enum class DiagnosticSource {
    /**
     * The lexer of the document's language. For the modeling languages this is the dsl-tools ANTLR lexer, run with an
     * IDE error listener. For environment descriptors it is the IDE's own JSON lexer.
     */
    LEXER,

    /**
     * The parser of the document's language. For the modeling languages this is the dsl-tools ANTLR parser, run with
     * an IDE error listener. For environment descriptors it is the IDE's own JSON tree builder.
     */
    PARSER,

    /**
     * A dsl-tools reader, which builds the semantic model. The finding sits at the place the reader reports. Its code
     * is the stable id of the dsl-tools diagnostic code, such as `kmodel.unknown-property` or
     * `kstates.initial-state-count`.
     */
    DSL_TOOLS,

    /**
     * The IDE itself. In this module that covers the internal failures. core-workspace uses this source for its findings
     * about file encoding, line separators, environment descriptors and modeling contexts.
     */
    IDE,
}

/**
 * Stable diagnostic codes for [Diagnostic.code] that core-document produces. They cover the lexer, the parser, the
 * nesting limit and internal failures.
 *
 * The findings of the dsl-tools readers, with source [DiagnosticSource.DSL_TOOLS], carry the stable ids of their
 * dsl-tools codes instead, so a new kind of finding in dsl-tools needs no constant here. Findings that only
 * core-workspace produces, such as those about file loading, environment descriptors and modeling contexts, have their
 * codes in `WorkspaceDiagnosticCodes` of core-workspace. A new workspace finding therefore never edits this file.
 */
public object DiagnosticCodes {
    /** Lexer error for unrecognized input, with source [DiagnosticSource.LEXER]. */
    public const val LEXER_ERROR: String = "lexer-error"

    /** Parser error for an unexpected or a missing token, with source [DiagnosticSource.PARSER]. */
    public const val SYNTAX_ERROR: String = "syntax-error"

    /** An unexpected internal failure that was caught and reported instead of crashing. It is an ERROR. */
    public const val INTERNAL_ERROR: String = "internal-error"

    /**
     * The text nests deeper than the parser follows. The ERROR sits at the brace that opens the first level beyond
     * [io.karpfen.ide.core.document.parse.NestingLimit.MAX_DEPTH]. If a parser runs out of stack before that level, it
     * sits at the start of the text. It reports a limit of the parser that the user can type and remove again, not a
     * defect.
     */
    public const val NESTING_TOO_DEEP: String = "nesting-too-deep"
}
