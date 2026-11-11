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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.lang.modelpath.PathMember
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.modelpath.PathResolution
import io.karpfen.ide.core.lang.modelpath.PathSegment
import io.karpfen.ide.core.lang.modelpath.ResolvedSegment
import io.karpfen.ide.core.lang.modelpath.PathSyntax
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.modelpath.indexAt
import io.karpfen.ide.core.lang.modelpath.pathOf
import io.karpfen.ide.core.lang.modelpath.pathSegments
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeInfo
import io.karpfen.ide.core.text.TextRange

/**
 * Model paths at the caret of a KStates document. It applies the [ModelPathResolver] in the scope that [scopes] gives.
 * KStates completion, hover and navigation share it for segment candidates, the segment under the caret and the type
 * of a `SETOBJ` source path. The class has no state.
 */
internal class KstatesPaths(private val scopes: KstatesPathScopes, private val paths: ModelPathResolver) {

    /** The segments that may follow [prefix], the path text before the caret, at the caret of [context]. */
    fun completions(request: FeatureRequest, context: CaretContext, prefix: String): List<PathMember> =
        paths.completions(prefix, scopes.scopeAt(request, context).path)

    /** The segment of the path written in [pathRange] at the caret of [context], or null if the caret is on none. */
    fun segmentAt(request: FeatureRequest, context: CaretContext, pathRange: TextRange): PathHit? {
        val segments = pathSegments(request.document.text.text, pathRange)
        val index = segments.indexAt(context.offset).takeIf { it >= 0 && segments[it].name.isNotEmpty() } ?: return null
        val scope = scopes.scopeAt(request, context)
        return PathHit(scope, segments, index, paths.resolve(segments.pathOf(index + 1), scope.path))
    }

    /** The type reached by [path], such as the source path of `SETOBJ`, at the caret of [context], or null. */
    fun typeOf(request: FeatureRequest, context: CaretContext, path: String): TypeInfo? {
        val scope = scopes.scopeAt(request, context).path
        return paths.resolve(path, scope).type?.takeIf { !it.isPrimitive }?.let { scope.type(it.name) }
    }

    /**
     * The type of the binding that the `WITH MACRO("m", …) AS "x"` statement at the caret of [context] declares. It is
     * the RETURNS type of `m`, or null if unknown.
     */
    fun declaredBindingType(request: FeatureRequest, context: CaretContext): PathType? {
        val macro = KstatesBlocks.boundMacroOf(KstatesBlocks.withConstructOf(context.statement)) ?: return null
        return MacroSignatures(DocumentDeclarations(request.document).located().map { it.outcome.index }).returns(macro)
    }
}

/** A resolved path segment at the caret. It is the segment at [index] of [segments], with the [resolution] up to it. */
internal class PathHit(val scope: KstatesScope, val segments: List<PathSegment>, val index: Int, val resolution: PathResolution) {

    /** The segment under the caret. */
    val segment: PathSegment get() = segments[index]

    /** The resolution of the segment. Its member is null if the segment is unresolved. */
    val resolved: ResolvedSegment? get() = resolution.segments.getOrNull(index)

    /** The member the segment resolved to, or null. */
    val member: PathMember? get() = resolved?.member

    /** The path up to and including the segment, joined by `->` without blanks. */
    val path: String get() = segments.pathOf(index + 1)

    /** True for the `event` root while the event type cannot be determined. */
    val isUndeterminedEvent: Boolean
        get() = member?.let { it.origin == PathOrigin.ROOT && it.name == PathSyntax.EVENT } == true && scope.path.eventType == null
}
