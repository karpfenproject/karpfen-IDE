package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/**
 * A declared symbol.
 *
 * @property kind what the symbol is.
 * @property name the declared name without quotes (empty for anonymous objects).
 * @property nameRange the name inside the quotes (may be empty for `""`); lies within [range].
 * @property range the whole declaring construct (e.g. the full `STATE "s" { … }`).
 * @property container name of the enclosing symbol where the language nests declarations, null at top level:
 *   SIMPLE_PROPERTY/HAS_RELATION/KNOWS_RELATION → the type; OBJECT → the enclosing object's outline name (its key, or
 *   `:T` for an anonymous object; null for the root object); STATE → the parent state; MACRO_PARAMETER → the macro;
 *   BINDING → the state whose action block contains it.
 * @property typeName the declared type as written, without quotes, where the construct names one: OBJECT → its type
 *   (`T` of `"key":"T"`); SIMPLE_PROPERTY → the primitive (`number`); HAS_RELATION/KNOWS_RELATION → the target
 *   (`Vector`, or `list(Obstacle)`); MACRO_PARAMETER → the parameter type (`Vector`, `number`, `list(Obstacle)`);
 *   MACRO → its RETURNS expression (`number`, `reference(Obstacle)`); STATEMACHINE → the `ATTACHED TO` type. Null
 *   for every other kind and when the type is missing in broken text.
 */
public data class SymbolDeclaration(
    public val kind: SymbolKind,
    public val name: String,
    public val nameRange: TextRange,
    public val range: TextRange,
    public val container: String?,
    public val typeName: String? = null,
) {
    init {
        require(nameRange in range) { "nameRange $nameRange must lie within range $range" }
    }
}

/**
 * A reference to a symbol by name; resolution happens in core-lang.
 *
 * @property kind what the name refers to.
 * @property name the referenced name without quotes; for [ReferenceKind.MODEL_PATH] the whole path with blanks
 *   removed (e.g. `a->b->c`, also for `$( a -> b )` in EVAL bodies).
 * @property range the name inside the quotes (or the path inside `$( … )` without surrounding blanks, or the name after
 *   `@`, in EVAL bodies).
 * @property qualifier context needed to resolve the name, null where none applies:
 *   KModel PROPERTY_KEY/RELATION_KEY → the enclosing object's type name; KStates RELATION_KEY (the relation argument of
 *   `SETOBJ`/`APPENDOBJ`) → the source model path (the first argument, e.g. `self->room`), whose type declares the
 *   relation; EVENT_NAME → the event domain; every other kind → null.
 */
public data class SymbolReference(
    public val kind: ReferenceKind,
    public val name: String,
    public val range: TextRange,
    public val qualifier: String?,
)

/** Kind of a [FoldingRange]. */
public enum class FoldingKind {
    /** A brace block spanning more than one line. */
    BLOCK,

    /** The body of an EVAL block. */
    EMBEDDED_CODE,

    /** A run of at least two consecutive comment lines. */
    COMMENT,
}

/**
 * A foldable region.
 *
 * @property range the region; it starts on one line and ends on a later one.
 * @property kind what the region holds.
 */
public data class FoldingRange(public val range: TextRange, public val kind: FoldingKind)
