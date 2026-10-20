package io.karpfen.ide.core.document

import io.karpfen.ide.core.text.TextRange

/**
 * A problem reported for a range of a document (spec §3.2). Library code turns every parse/analysis failure into a
 * diagnostic instead of throwing (spec §1, Errors).
 *
 * @property range the affected range in the coordinates of the document version the diagnostic was computed for; may
 *   be empty (e.g. "missing `}`" at end of file).
 * @property code a stable machine-readable code, see [DiagnosticCodes].
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

    /** The IDE itself (file encoding, line separators, environment descriptors, modeling contexts, EVAL warnings). */
    IDE,
}

/** Stable diagnostic codes ([Diagnostic.code]). */
public object DiagnosticCodes {
    /** Lexer error (unrecognized input); source [DiagnosticSource.LEXER]. */
    public const val LEXER_ERROR: String = "lexer-error"

    /** Parser error (unexpected or missing token); source [DiagnosticSource.PARSER]. */
    public const val SYNTAX_ERROR: String = "syntax-error"

    /** `//` inside an EVAL body starts a karpfen comment (WARNING, spec §3.1). */
    public const val EVAL_DOUBLE_SLASH: String = "eval-double-slash"

    /** A dsl-tools converter rejected the file; source [DiagnosticSource.DSL_TOOLS]. */
    public const val DSL_TOOLS_ERROR: String = "dsl-tools"

    /** The file mixes line separators (INFO, reported once when loading). */
    public const val MIXED_LINE_SEPARATORS: String = "mixed-line-separators"

    /** The file is not valid UTF-8 and was decoded with replacement characters (WARNING). */
    public const val MALFORMED_ENCODING: String = "malformed-encoding"

    /** An environment descriptor is not valid JSON or misses required keys (ERROR). */
    public const val DESCRIPTOR_INVALID: String = "descriptor-invalid"

    /** An environment descriptor contains an unknown key (WARNING). */
    public const val DESCRIPTOR_UNKNOWN_KEY: String = "descriptor-unknown-key"

    /** An environment descriptor names a file that does not exist (ERROR). */
    public const val DESCRIPTOR_MISSING_FILE: String = "descriptor-missing-file"

    /** An implicit modeling context contains several domain metamodels (INFO on each, spec §4). */
    public const val CONTEXT_MULTIPLE_METAMODELS: String = "context-multiple-metamodels"

    /** A modeling context has models or state machines but no metamodel to analyze them with (INFO). */
    public const val CONTEXT_NO_METAMODEL: String = "context-no-metamodel"

    /** An unexpected internal failure that was caught and reported instead of crashing (ERROR). */
    public const val INTERNAL_ERROR: String = "internal-error"
}
