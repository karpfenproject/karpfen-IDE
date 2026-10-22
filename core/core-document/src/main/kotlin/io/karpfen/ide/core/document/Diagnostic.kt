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
    /** The dsl-tools ANTLR lexer, run with an IDE error listener. */
    LEXER,

    /** The dsl-tools ANTLR parser, run with an IDE error listener. */
    PARSER,

    /** An exception from a dsl-tools converter (semantic model construction). */
    DSL_TOOLS,

    /**
     * The IDE itself (EVAL warnings here; file encoding, line separators, environment descriptors and modeling contexts
     * in core-workspace).
     */
    IDE,
}

/**
 * Stable diagnostic codes ([Diagnostic.code]) produced by core-document: lexer, parser, EVAL scanning, dsl-tools
 * conversion and internal failures. Codes of findings that only core-workspace produces (file loading, environment
 * descriptors, modeling contexts) are `WorkspaceDiagnosticCodes` of core-workspace, so a new workspace finding never
 * edits this file.
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

    /** A dsl-tools converter rejected the file; source [DiagnosticSource.DSL_TOOLS]. */
    public const val DSL_TOOLS_ERROR: String = "dsl-tools"

    /** An unexpected internal failure that was caught and reported instead of crashing (ERROR). */
    public const val INTERNAL_ERROR: String = "internal-error"
}
