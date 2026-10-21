package io.karpfen.ide.core.document.syntax

/**
 * The lexical facts of the karpfen languages that several layers rely on, declared once (decision 46, one source per
 * fact): the tokenizers, tree walkers and EVAL scanner of this module, the language services (core-lang) and the
 * editor (ui-editor) read them here, so e.g. the completion's replace range and the editor's word rule always agree.
 * `KarpfenSyntaxTest` checks them against the generated lexers.
 */
public object KarpfenSyntax {

    /**
     * The KMeta primitive property types (dsl-tools' `SimplePropertyType`) as written in the files; KStates uses the
     * same names in TAKES/RETURNS types.
     */
    public val PRIMITIVE_TYPES: Set<String> = setOf("number", "string", "boolean")

    /** The KStates action operations, by their upper-case names (the grammar accepts both cases). */
    public val KSTATES_OPERATIONS: Set<String> = setOf("SET", "APPEND", "SETLIST", "DROPLIST", "SETOBJ", "APPENDOBJ", "DROPOBJ")

    /** The KStates operations whose second argument is a relation of the type their first argument (a path) names. */
    public val KSTATES_RELATION_OPERATIONS: Set<String> = setOf("SETOBJ", "APPENDOBJ")

    /** The KStates keywords that open an action block (`EVENT` is an operation there), by their upper-case names. */
    public val KSTATES_ACTION_BLOCKS: Set<String> = setOf("ENTRY", "DO", "IF", "WITH")

    /** The KStates keyword that opens a condition block (`EVENT` is a condition keyword there). */
    public const val KSTATES_CONDITION_BLOCK: String = "CONDITION"

    /** The KStates keywords that open every other block (structure, macro definitions, EVAL bodies). */
    public val KSTATES_STRUCTURE_BLOCKS: Set<String> = setOf(
        "STATEMACHINE", "STATES", "INITIAL", "STATE", "TRANSITIONS", "TRANSITION", "SPLIT", "JOIN", "MACROS", "MACRO",
        "DEFINITION", "EVAL",
    )

    /**
     * True for the characters of identifiers and model-path segments, `[A-Za-z0-9_]` (the lexers' identifier rule; a
     * digit only cannot start one).
     */
    public fun isIdentifierChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'
}
