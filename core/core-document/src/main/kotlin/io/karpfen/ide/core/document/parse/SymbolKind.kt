package io.karpfen.ide.core.document.parse

/** What a [SymbolDeclaration] or [OutlineNode] declares. */
public enum class SymbolKind {
    /** KMeta `type "N" "D" { … }`. */
    TYPE,

    /** KMeta `prop("k", "number")`. */
    SIMPLE_PROPERTY,

    /** KMeta `has("k", "T")` (containment). */
    HAS_RELATION,

    /** KMeta `knows("k", "T")` (reference). */
    KNOWS_RELATION,

    /** KModel `make object "key":"T" { … }`. */
    OBJECT,

    /** KModel `prop("k") -> "v"`. */
    PROPERTY_ASSIGNMENT,

    /** KModel `has("k") -> make object …`. */
    HAS_ASSIGNMENT,

    /** KModel `knows("k") -> "obj"`. */
    KNOWS_ASSIGNMENT,

    /** KStates `STATEMACHINE ATTACHED TO "T" { … }`. */
    STATEMACHINE,

    /** KStates `STATE "s" { … }` (also `INITIAL STATE`). */
    STATE,

    /** KStates `ENTRY { … }`. */
    ENTRY_BLOCK,

    /** KStates `DO { … }`. */
    DO_BLOCK,

    /** KStates `TRANSITION "a" -> "b" { … }`. */
    TRANSITION,

    /** KStates `SPLIT "a" -> "b", "c" { … }`. */
    SPLIT,

    /** KStates `JOIN "a", "b" -> "c" { … }`. */
    JOIN,

    /** KStates `MACRO "m" { … }` in the MACROS block. */
    MACRO,

    /** KStates `TAKES("p", "T")`. */
    MACRO_PARAMETER,

    /** KStates `WITH MACRO(…) AS "x" { … }`. */
    BINDING,

    /** An environment descriptor key with its value, `"model": "robot.kmodel"`. */
    KEY,

    /** An entry of a list in an environment descriptor, such as one state machine of `stateMachines`. */
    ENTRY,
}

/** What a [SymbolReference] refers to. */
public enum class ReferenceKind {
    /** A type name declared in a `.kmeta` file. */
    TYPE,

    /** `number`, `string` or `boolean`. */
    PRIMITIVE_TYPE,

    /** A state name in a transition, SPLIT or JOIN. */
    STATE,

    /** A macro name in `MACRO("m", …)` or `@m(` inside EVAL. */
    MACRO,

    /** An object key in a KModel `knows(…) -> "obj"`. */
    OBJECT,

    /** A simple property key in KModel `prop("k")`; qualifier = the enclosing object's type. */
    PROPERTY_KEY,

    /** A relation key in KModel `has("k")`/`knows("k")` or the relation argument of SETOBJ/APPENDOBJ. */
    RELATION_KEY,

    /** A `->`-separated model path (action left sides, `VALUE`, `IF IN SCOPE`, macro arguments, `$(…)`). */
    MODEL_PATH,

    /** The event name argument of `EVENT("d", "n")`. */
    EVENT_NAME,

    /** The event domain argument of `EVENT("d", "n")`. */
    EVENT_DOMAIN,
}
