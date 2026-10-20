package io.karpfen.ide.core.lang.modelpath

import meta.Metamodel

/**
 * Shared mechanic "model-path resolution over a metamodel" (spec §5): resolves `->`-separated paths segment by
 * segment over the props/has/knows of a type. Used by KStates completion, hover and navigation. Owned by WP-LANG,
 * which may refine these types.
 *
 * Resolution rules: the first segment is looked up in [PathScope.bindings] (WITH bindings, macro parameters), then
 * `self` (the root type itself), then `event` (the event type, or the union of all event types' fields when it is not
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
public fun ModelPathResolver(): ModelPathResolver = MetamodelPathResolver()

/**
 * Where a path starts.
 *
 * @property metamodel the context's domain metamodel.
 * @property rootType the type paths start at (the state machine's `ATTACHED TO` type), or null if unknown.
 * @property events the event metamodel, or null.
 * @property eventType the event type of the current transition's `EVENT(…)` clause when determinable, else null.
 * @property bindings names bound in scope (WITH … AS, macro parameters) with their declared types.
 */
public data class PathScope(
    public val metamodel: Metamodel?,
    public val rootType: String?,
    public val events: Metamodel? = null,
    public val eventType: String? = null,
    public val bindings: Map<String, PathType> = emptyMap(),
)

/** A type reached by a path: a metamodel type name or a primitive (`number`, `string`, `boolean`), maybe a list. */
public data class PathType(public val name: String, public val isList: Boolean, public val isPrimitive: Boolean)

/** A member that can follow a path prefix. */
public data class PathMember(public val name: String, public val kind: PathMemberKind, public val type: PathType)

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

/** [ModelPathResolver] over dsl-tools `meta.Metamodel`. */
internal class MetamodelPathResolver : ModelPathResolver {
    override fun resolve(path: String, scope: PathScope): PathResolution = TODO("WP-LANG: resolve model path")

    override fun completions(prefix: String, scope: PathScope): List<PathMember> = TODO("WP-LANG: path completions")
}
