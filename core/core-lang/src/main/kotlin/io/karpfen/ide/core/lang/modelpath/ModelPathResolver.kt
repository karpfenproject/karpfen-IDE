/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.core.lang.modelpath

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.symbols.MemberInfo
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.lang.symbols.TypeOrigin

/**
 * The shared mechanic of model path resolution. It resolves paths separated by `->` segment by segment over the
 * `prop`, `has` and `knows` members of the types of a [PathScope]. It is built on the `TypeInfo` values of the
 * `TypeCatalog`, which the IDE owns, and not on dsl-tools classes. So no dsl-tools type appears in the API. KStates
 * completion, hover and navigation use it.
 *
 * The first segment is looked up in this order. First come the [PathScope.bindings], which are WITH bindings and macro
 * parameters. Then comes `self`, the root type itself. Then comes `event`, which is the event type. When the event type
 * cannot be determined, `event` has the union of the members of all event types. Last come the members of
 * [PathScope.rootType]. Every further segment is looked up in the members of the type of the previous segment. A list
 * continues with its element type. Primitive and unknown types end the resolution. Paths are compared without blanks,
 * so `a -> b` is `a->b`.
 */
public interface ModelPathResolver {

    /** Resolves [path], such as `boundingBox->position->x`, in [scope]. It never throws. */
    public fun resolve(path: String, scope: PathScope): PathResolution

    /**
     * The members that may follow [prefix], the text before the caret, such as `boundingBox->` or `boundingBox->pos`.
     * These are the members of the type that all complete segments of [prefix] reach. It never throws.
     */
    public fun completions(prefix: String, scope: PathScope): List<PathMember>
}

/** The default [ModelPathResolver]. */
public fun ModelPathResolver(): ModelPathResolver = TypePathResolver()

/**
 * Where a path starts.
 *
 * @property types the types visible from the document, as `TypeCatalog.types` lists them, including domain and event
 *   types.
 * @property rootType the type paths start at, which is the `ATTACHED TO` type of the state machine, or null if unknown.
 * @property eventType the event type of the `EVENT(...)` clause of the current transition when it can be determined,
 *   or else null.
 * @property bindings the names bound in scope with their declared types. These are `WITH ... AS` bindings and macro
 *   parameters.
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

/**
 * A type reached by a path. It is a metamodel type name or one of the primitives `number`, `string` and `boolean`,
 * possibly as a list.
 */
public data class PathType(public val name: String, public val isList: Boolean, public val isPrimitive: Boolean) {
    public companion object {
        /** The type of [member], with its element type and whether it is a list or a primitive. */
        public fun of(member: MemberInfo): PathType = PathType(member.typeName, member.isList, member.isPrimitive)
    }
}

/**
 * A segment that can follow a path prefix or start a path. Type members wrap the [MemberInfo] of the
 * [TypeCatalog][io.karpfen.ide.core.lang.symbols.TypeCatalog] instead of copying it, so there is one value family for
 * members.
 *
 * @property origin [PathOrigin.MEMBER] for a type member, [PathOrigin.ROOT] for `self` and `event`, or
 *   [PathOrigin.BINDING].
 * @property type the type the segment reaches.
 * @property member the member for [PathOrigin.MEMBER], with its kind, declaration and signature. It is null for roots
 *   and bindings.
 */
public data class PathMember(
    public val name: String,
    public val origin: PathOrigin,
    public val type: PathType,
    public val member: MemberInfo? = null,
) {
    init {
        require((origin == PathOrigin.MEMBER) == (member != null)) { "exactly the MEMBER segments wrap a MemberInfo" }
    }

    /** The kind of the member, which is `prop`, `has` or `knows`, or null for roots and bindings. */
    public val kind: MemberKind? get() = member?.kind

    /** Where the member is declared, for navigation. It is null for roots, bindings and unknown declarations. */
    public val declaration: Location? get() = member?.declaration

    public companion object {
        /** The segment for type member [member]. */
        public fun of(member: MemberInfo): PathMember = PathMember(member.name, PathOrigin.MEMBER, PathType.of(member), member)
    }
}

/** Where a [PathMember] comes from. */
public enum class PathOrigin {
    /** A `prop`, `has` or `knows` member of the type of the previous segment. */
    MEMBER,

    /** The `self` or `event` root. */
    ROOT,

    /** A WITH binding or macro parameter. */
    BINDING,
}

/**
 * The result of [ModelPathResolver.resolve]. [segments] has one entry per segment. A segment with a null member is
 * unresolved, and all later segments are then unresolved too. [type] is the type of the whole path, or null if any
 * segment is unresolved.
 */
public data class PathResolution(public val segments: List<ResolvedSegment>, public val type: PathType?)

/**
 * One segment of a resolved path and the member it resolved to, or null.
 *
 * @property owner the type whose member the segment is. For a first member segment it is the root type. For a segment
 *   found by the union lookup over event types it is the event type that has the member. It is null for roots,
 *   bindings and unresolved segments.
 */
public data class ResolvedSegment(public val name: String, public val member: PathMember?, public val owner: TypeInfo? = null)

/** The [ModelPathResolver] over the `TypeInfo` members of a [PathScope]. It has no state. */
internal class TypePathResolver : ModelPathResolver {

    override fun resolve(path: String, scope: PathScope): PathResolution {
        val names = splitPath(path)
        val segments = ArrayList<ResolvedSegment>(names.size)
        var candidates: List<TypeInfo>? = null
        for ((index, name) in names.withIndex()) {
            val found = if (index == 0) first(name, scope) else candidates?.let { member(name, it) }
            if (found == null) {
                names.drop(index).forEach { segments += ResolvedSegment(it, member = null) }
                return PathResolution(segments, type = null)
            }
            segments += found
            candidates = found.member?.let { typesAfter(it, scope) }
        }
        return PathResolution(segments, segments.lastOrNull()?.member?.type)
    }

    override fun completions(prefix: String, scope: PathScope): List<PathMember> {
        val cleaned = KarpfenSyntax.normalizeModelPath(prefix)
        val cut = cleaned.lastIndexOf(SEPARATOR)
        if (cut < 0) return roots(scope)
        val resolution = resolve(cleaned.substring(0, cut), scope)
        val last = resolution.segments.lastOrNull()?.member ?: return emptyList()
        if (resolution.type == null) return emptyList()
        return typesAfter(last, scope).orEmpty().flatMap { it.members }.distinctBy { it.name }.map(PathMember::of)
    }

    /**
     * The candidates of a first segment. These are bindings, `self`, `event`, and then the members of the root type.
     */
    private fun roots(scope: PathScope): List<PathMember> {
        val bindings = scope.bindings.map { (name, type) -> PathMember(name, PathOrigin.BINDING, type) }
        val self = listOfNotNull(selfOf(scope))
        val event = listOfNotNull(eventOf(scope))
        val members = scope.rootType?.let(scope::type)?.members.orEmpty().map(PathMember::of)
        return (bindings + self + event + members).distinctBy { it.name }
    }

    /** The first segment [name], looked up in the order that [ModelPathResolver] describes. */
    private fun first(name: String, scope: PathScope): ResolvedSegment? {
        scope.bindings[name]?.let { return ResolvedSegment(name, PathMember(name, PathOrigin.BINDING, it)) }
        if (name == SELF) selfOf(scope)?.let { return ResolvedSegment(name, it) }
        if (name == EVENT) eventOf(scope)?.let { return ResolvedSegment(name, it) }
        return scope.rootType?.let(scope::type)?.let { member(name, listOf(it)) }
    }

    /** The member [name] of the first of [owners] that has one, or null. */
    private fun member(name: String, owners: List<TypeInfo>): ResolvedSegment? {
        for (owner in owners) {
            owner.member(name)?.let { return ResolvedSegment(name, PathMember.of(it), owner) }
        }
        return null
    }

    /**
     * The types whose members may follow [member]. This is its type, or every event type after an undetermined `event`.
     */
    private fun typesAfter(member: PathMember, scope: PathScope): List<TypeInfo>? {
        if (member.origin == PathOrigin.ROOT && member.name == EVENT && scope.eventType == null) return scope.eventTypes
        if (member.type.isPrimitive) return null
        return scope.type(member.type.name)?.let(::listOf)
    }

    /** The `self` root of the root type, or null if the root type is unknown. */
    private fun selfOf(scope: PathScope): PathMember? =
        scope.rootType?.let { PathMember(SELF, PathOrigin.ROOT, PathType(it, isList = false, isPrimitive = false)) }

    /**
     * The `event` root. Its type is the determined event type, or else the pseudo type `event` that stands for every
     * event type. It is null when there are no event types.
     */
    private fun eventOf(scope: PathScope): PathMember? = when {
        scope.eventType != null -> PathMember(EVENT, PathOrigin.ROOT, PathType(scope.eventType, isList = false, isPrimitive = false))
        scope.eventTypes.isNotEmpty() -> PathMember(EVENT, PathOrigin.ROOT, PathType(EVENT, isList = false, isPrimitive = false))
        else -> null
    }

    /** The segments of [path] without blanks, so `a -> b` gives `a` and `b`. A blank path gives no segments. */
    private fun splitPath(path: String): List<String> {
        val cleaned = KarpfenSyntax.normalizeModelPath(path)
        return if (cleaned.isEmpty()) emptyList() else cleaned.split(SEPARATOR)
    }

    private companion object {
        const val SELF = PathSyntax.SELF
        const val EVENT = PathSyntax.EVENT
        const val SEPARATOR = PathSyntax.SEPARATOR
    }
}
