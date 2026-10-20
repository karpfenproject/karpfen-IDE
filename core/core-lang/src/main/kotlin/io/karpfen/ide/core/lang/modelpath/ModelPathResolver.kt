package io.karpfen.ide.core.lang.modelpath

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * Shared mechanic "model-path resolution" (spec §5): resolves `->`-separated paths segment by segment over the
 * members (props/has/knows) of the types of a [PathScope]. Built on the IDE-owned `TypeInfo` values of the
 * `TypeCatalog`, not on dsl-tools classes. Used by KStates completion, hover and navigation. Owned by WP-LANG, which
 * may refine these types.
 *
 * Resolution rules: the first segment is looked up in [PathScope.bindings] (WITH bindings, macro parameters), then
 * `self` (the root type itself), then `event` (the event type, or the union of all event types' members when it is not
 * determinable), then the members of [PathScope.rootType]; every further segment in the members of the previous
 * segment's type. Primitive and unknown types end the resolution.
 */
public interface ModelPathResolver {

    /** Resolves [path] (e.g. `boundingBox->position->x`) in [scope]. Never throws. */
    public fun resolve(path: String, scope: PathScope): PathResolution

    /**
     * The members that may follow [prefix] (the text before the caret, e.g. `boundingBox->` or `boundingBox->pos`):
     * the members of the type reached by all complete segments of [prefix]. Never throws.
     */
    public fun completions(prefix: String, scope: PathScope): List<PathMember>
}

/** The default [ModelPathResolver]. */
public fun ModelPathResolver(): ModelPathResolver = TypePathResolver()

/**
 * Where a path starts.
 *
 * @property types the types visible from the document (`TypeCatalog.types`), domain and event types included.
 * @property rootType the type paths start at (the state machine's `ATTACHED TO` type), or null if unknown.
 * @property eventType the event type of the current transition's `EVENT(…)` clause when determinable, else null.
 * @property bindings names bound in scope (WITH … AS, macro parameters) with their declared types.
 */
public data class PathScope(
    public val types: List<TypeInfo>,
    public val rootType: String?,
    public val eventType: String? = null,
    public val bindings: Map<String, PathType> = emptyMap(),
) {
    /** The type named [name], or null. */
    public fun type(name: String): TypeInfo? = types.firstOrNull { it.name == name }

    /** The types of the event metamodel. */
    public val eventTypes: List<TypeInfo> get() = types.filter { it.origin == TypeOrigin.EVENTS }
}

/** A type reached by a path: a metamodel type name or a primitive (`number`, `string`, `boolean`), maybe a list. */
public data class PathType(public val name: String, public val isList: Boolean, public val isPrimitive: Boolean)

/**
 * A member that can follow a path prefix.
 *
 * @property declaration where the member is declared (for navigation), or null (roots, bindings, unknown).
 */
public data class PathMember(
    public val name: String,
    public val kind: PathMemberKind,
    public val type: PathType,
    public val declaration: Location? = null,
)

/** Kind of a [PathMember]. */
public enum class PathMemberKind {
    /** `prop(…)`. */
    PROPERTY,

    /** `has(…)`. */
    HAS,

    /** `knows(…)`. */
    KNOWS,

    /** The `self` or `event` root. */
    ROOT,

    /** A WITH binding or macro parameter. */
    BINDING,
}

/**
 * Result of [ModelPathResolver.resolve]: one entry per segment (null member = unresolved, later segments are then
 * unresolved too) and the type of the whole path (null if any segment is unresolved).
 */
public data class PathResolution(public val segments: List<ResolvedSegment>, public val type: PathType?)

/** One segment of a resolved path and the member it resolved to (or null). */
public data class ResolvedSegment(public val name: String, public val member: PathMember?)

/** [ModelPathResolver] over the `TypeInfo` members of a [PathScope]. */
internal class TypePathResolver : ModelPathResolver {
    override fun resolve(path: String, scope: PathScope): PathResolution = TODO("WP-LANG: resolve model path")

    override fun completions(prefix: String, scope: PathScope): List<PathMember> = TODO("WP-LANG: path completions")
}
