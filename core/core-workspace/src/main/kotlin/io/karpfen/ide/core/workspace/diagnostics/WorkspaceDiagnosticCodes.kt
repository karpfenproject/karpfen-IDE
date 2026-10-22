package io.karpfen.ide.core.workspace.diagnostics

/**
 * Stable codes (`Diagnostic.code`) of the findings only core-workspace produces, all with source `DiagnosticSource.IDE`:
 * file loading (`SourceLoader`, files and directories that cannot be read), environment descriptors
 * (`EnvironmentDescriptorReader`) and modeling contexts (`ContextResolver`). The lexer, parser, EVAL, dsl-tools and
 * internal codes are core-document's `DiagnosticCodes`. A new workspace finding adds its code here, so every code has
 * exactly one declaration. A leaf package, so every producer may depend on it without package cycles.
 */
public object WorkspaceDiagnosticCodes {
    /** The file mixes line separators (INFO, reported once when loading). */
    public const val MIXED_LINE_SEPARATORS: String = "mixed-line-separators"

    /**
     * A file or a directory of the workspace cannot be read, so it (or the files in it) was not analyzed (ERROR at the
     * start, on the file or directory itself; a silent pass would let `karpfen validate` accept a tree it never saw).
     */
    public const val UNREADABLE: String = "unreadable"

    /** The file is not valid UTF-8 and was decoded with replacement characters (WARNING). */
    public const val MALFORMED_ENCODING: String = "malformed-encoding"

    /** An environment descriptor is not valid JSON, misses required keys, repeats a key or has a wrong value (ERROR). */
    public const val DESCRIPTOR_INVALID: String = "descriptor-invalid"

    /** An environment descriptor contains an unknown key (WARNING). */
    public const val DESCRIPTOR_UNKNOWN_KEY: String = "descriptor-unknown-key"

    /** An environment descriptor names a file that does not exist (ERROR). */
    public const val DESCRIPTOR_MISSING_FILE: String = "descriptor-missing-file"

    /**
     * An implicit modeling context contains several domain metamodels (INFO on each; the alphabetically first is used).
     */
    public const val CONTEXT_MULTIPLE_METAMODELS: String = "context-multiple-metamodels"

    /** A modeling context has models or state machines but no metamodel to analyze them with (INFO). */
    public const val CONTEXT_NO_METAMODEL: String = "context-no-metamodel"
}
