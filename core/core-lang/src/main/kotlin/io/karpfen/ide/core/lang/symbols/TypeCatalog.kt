package io.karpfen.ide.core.lang.symbols

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * Shared mechanic "types of a modeling context" (spec §5: shared mechanics are separate collaborators, not copied per
 * language): the types visible from a document with their members and declaration [Location]s, as IDE-owned values,
 * so the dsl-tools `meta.*` model stays behind this interface. Used by completion, navigation and hover of all three
 * languages and by the `ModelPathResolver`. Owned by WP-LANG, which may refine it.
 *
 * Implementations are thread-safe and convert the dsl-tools metamodels of a context into [TypeInfo]s once per analysis
 * (memoized by `ContextAnalysis` identity, which changes with every analysis generation, keeping only the latest), so a
 * hover, a completion or a path segment never walks the mutable dsl-tools objects again (spec §1a, immutable values
 * across threads). The document's own declarations come from its parse and are not memoized.
 */
public interface TypeCatalog {

    /**
     * The types visible from [request]'s document, in this order (a name appears once, the first wins): the types
     * declared in the document itself when it is a `.kmeta` file (from its parse, so they are known before conversion),
     * then the context's domain metamodel, then its event types. Never throws.
     *
     * Event types ([TypeOrigin.EVENTS]) are the TYPE declarations of the context's events file itself (its
     * `AnalyzedFile.parse` syntax index), with members from the converted `ContextAnalysis.events` metamodel — never
     * all of `events.types`, which dsl-tools fills with the domain base types too (see `ContextAnalysis.events`). So a
     * provider listing event names or event types offers no domain type.
     */
    public fun types(request: FeatureRequest): List<TypeInfo>

    /** The visible type named [name], or null. */
    public fun type(request: FeatureRequest, name: String): TypeInfo? = types(request).firstOrNull { it.name == name }
}

/** The default [TypeCatalog] over the project analysis and the document's parse. */
public fun TypeCatalog(): TypeCatalog = ContextTypeCatalog()

/** Where a [TypeInfo] comes from. */
public enum class TypeOrigin {
    /** Declared in the requesting `.kmeta` document itself. */
    DOCUMENT,

    /** The context's domain metamodel. */
    DOMAIN,

    /** Declared in the context's event metamodel file (`EVENTS.kmeta`) itself; not the domain types it reuses. */
    EVENTS,
}

/**
 * A type and its members.
 *
 * @property description the KMeta description string, or null.
 * @property declaration the TYPE declaration's name range, or null if unknown.
 */
public data class TypeInfo(
    public val name: String,
    public val description: String?,
    public val members: List<MemberInfo>,
    public val origin: TypeOrigin,
    public val declaration: Location?,
) {
    /** The member named [name], or null. */
    public fun member(name: String): MemberInfo? = members.firstOrNull { it.name == name }

    /**
     * Hover text (spec §5, "type → its description and members"): `type <name>`, the description on the next line if
     * there is one, then a blank line and one [MemberInfo.signature] per line if there are members.
     */
    public val hoverText: String
        get() = buildString {
            append("type ").append(name)
            description?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
            if (members.isNotEmpty()) {
                append('\n')
                members.forEach { append('\n').append(it.signature) }
            }
        }
}

/**
 * A member of a type: `prop`, `has` or `knows`.
 *
 * @property typeName the element type (`number`, `Vector`, `Obstacle` for `list("Obstacle")`).
 * @property isList true for `list(…)`.
 * @property declaration the member declaration's name range, or null if unknown.
 */
public data class MemberInfo(
    public val name: String,
    public val kind: MemberKind,
    public val typeName: String,
    public val isList: Boolean,
    public val declaration: Location?,
) {
    /** True if [typeName] is a primitive (`number`, `string`, `boolean`). */
    public val isPrimitive: Boolean get() = typeName in PRIMITIVE_TYPES

    /** The hover signature (spec §5): `prop x: number`, `has position: Vector`, `knows obstacles: list(Obstacle)`. */
    public val signature: String
        get() = "${kind.keyword} $name: ${if (isList) "list($typeName)" else typeName}"

    public companion object {
        /** The KMeta primitive types. */
        public val PRIMITIVE_TYPES: Set<String> = setOf("number", "string", "boolean")
    }
}

/** Kind of a [MemberInfo]; [keyword] is how KMeta declares it. */
public enum class MemberKind(public val keyword: String) {
    /** `prop(…)`. */
    PROPERTY("prop"),

    /** `has(…)` (containment). */
    HAS("has"),

    /** `knows(…)` (reference). */
    KNOWS("knows"),
}

/**
 * [TypeCatalog] reading the document's own TYPE declarations and the context's converted metamodels (memoized per
 * `ContextAnalysis`, see [TypeCatalog]).
 */
internal class ContextTypeCatalog : TypeCatalog {
    override fun types(request: FeatureRequest): List<TypeInfo> =
        TODO("WP-LANG: document types, then domain and event metamodel types of ${request.document.path}")
}
