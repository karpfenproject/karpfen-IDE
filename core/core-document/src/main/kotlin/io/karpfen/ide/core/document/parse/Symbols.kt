package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/**
 * A declared symbol (spec §3.3).
 *
 * @property name the declared name without quotes (empty for anonymous objects).
 * @property nameRange the name inside the quotes (may be empty for `""`); lies within [range].
 * @property range the whole declaring construct (e.g. the full `STATE "s" { … }`).
 * @property container name of the enclosing symbol where the language nests declarations: the parent state of a
 *   STATE, the type of a property, the macro of a parameter; null at top level.
 */
public data class SymbolDeclaration(
    public val kind: SymbolKind,
    public val name: String,
    public val nameRange: TextRange,
    public val range: TextRange,
    public val container: String?,
) {
    init {
        require(nameRange in range) { "nameRange $nameRange must lie within range $range" }
    }
}

/**
 * A reference to a symbol by name (spec §3.3); resolution happens in core-lang.
 *
 * @property name the referenced name without quotes (for [ReferenceKind.MODEL_PATH] the whole path, e.g. `a->b->c`).
 * @property range the name inside the quotes (or inside `$( … )` / after `@` in EVAL bodies).
 * @property qualifier context needed to resolve the name: for PROPERTY_KEY/RELATION_KEY the enclosing object's type
 *   name, for EVENT_NAME the event domain; null otherwise.
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

/** A foldable region (spec §3.3); [range] starts on one line and ends on a later one. */
public data class FoldingRange(public val range: TextRange, public val kind: FoldingKind)
