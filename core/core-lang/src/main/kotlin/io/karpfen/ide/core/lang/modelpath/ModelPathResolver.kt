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
 * The shared mechanic of model-path resolution: resolves `->`-separated paths segment by segment over the members
 * (props/has/knows) of the types of a [PathScope]. Built on the IDE-owned `TypeInfo` values of the `TypeCatalog`, not
 * on dsl-tools classes, so no dsl-tools type appears in the API. Used by KStates completion, hover and navigation.
 *
 * Resolution rules: the first segment is looked up in [PathScope.bindings] (WITH bindings, macro parameters), then
 * `self` (the root type itself), then `event` (the event type, or the union of all event types' members when it is not
 * determinable), then the members of [PathScope.rootType]; every further segment in the members of the previous
 * segment's type (a list continues with its element type). Primitive and unknown types end the resolution. Paths are
 * compared without blanks (`a -> b` is `a->b`).
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
public data class PathType(public val name: String, public val isList: Boolean, public val isPrimitive: Boolean) {
    public companion object {
        /** The type of [member] (element type, list flag, primitive flag). */
        public fun of(member: MemberInfo): PathType = PathType(member.typeName, member.isList, member.isPrimitive)
    }
}

/**
 * A segment that can follow a path prefix (or start a path). Type members wrap the
 * [TypeCatalog][io.karpfen.ide.core.lang.symbols.TypeCatalog]'s [MemberInfo] instead of copying it, so there is one
 * value family for members.
 *
 * @property origin a type [PathOrigin.MEMBER], the `self`/`event` [PathOrigin.ROOT] or a [PathOrigin.BINDING].
 * @property type the type the segment reaches.
 * @property member the member for [PathOrigin.MEMBER] (kind, declaration, signature), null for roots and bindings.
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

    /** The member's kind (`prop`/`has`/`knows`), or null for roots and bindings. */
    public val kind: MemberKind? get() = member?.kind

    /** Where the member is declared (for navigation), or null (roots, bindings, unknown). */
    public val declaration: Location? get() = member?.declaration

    public companion object {
        /** The segment for type member [member]. */
        public fun of(member: MemberInfo): PathMember = PathMember(member.name, PathOrigin.MEMBER, PathType.of(member), member)
    }
}

/** Where a [PathMember] comes from. */
public enum class PathOrigin {
    /** A member (`prop`, `has`, `knows`) of the previous segment's type. */
    MEMBER,

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

/**
 * One segment of a resolved path and the member it resolved to (or null).
 *
 * @property owner the type whose member the segment is (the root type for a first member segment, the event type a
 *   union lookup found it in), null for roots, bindings and unresolved segments.
 */
public data class ResolvedSegment(public val name: String, public val member: PathMember?, public val owner: TypeInfo? = null)

/** [ModelPathResolver] over the `TypeInfo` members of a [PathScope]. Stateless. */
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

    /** The candidates of a first segment: bindings, `self`, `event`, then the root type's members. */
    private fun roots(scope: PathScope): List<PathMember> {
        val bindings = scope.bindings.map { (name, type) -> PathMember(name, PathOrigin.BINDING, type) }
        val self = listOfNotNull(selfOf(scope))
        val event = listOfNotNull(eventOf(scope))
        val members = scope.rootType?.let(scope::type)?.members.orEmpty().map(PathMember::of)
        return (bindings + self + event + members).distinctBy { it.name }
    }

    /** The first segment [name], looked up in the order of the class documentation's resolution rules. */
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

    /** The types whose members may follow [member]: its type, or every event type after an undetermined `event`. */
    private fun typesAfter(member: PathMember, scope: PathScope): List<TypeInfo>? {
        if (member.origin == PathOrigin.ROOT && member.name == EVENT && scope.eventType == null) return scope.eventTypes
        if (member.type.isPrimitive) return null
        return scope.type(member.type.name)?.let(::listOf)
    }

    /** The `self` root, of the root type; null if the root type is unknown. */
    private fun selfOf(scope: PathScope): PathMember? =
        scope.rootType?.let { PathMember(SELF, PathOrigin.ROOT, PathType(it, isList = false, isPrimitive = false)) }

    /**
     * The `event` root: of the determined event type, else of the pseudo type `event` standing for every event type;
     * null without event types.
     */
    private fun eventOf(scope: PathScope): PathMember? = when {
        scope.eventType != null -> PathMember(EVENT, PathOrigin.ROOT, PathType(scope.eventType, isList = false, isPrimitive = false))
        scope.eventTypes.isNotEmpty() -> PathMember(EVENT, PathOrigin.ROOT, PathType(EVENT, isList = false, isPrimitive = false))
        else -> null
    }

    /** The segments of [path] without blanks (`a -> b` → `a`, `b`); empty for a blank path. */
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
