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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.lang.modelpath.PathOrigin
import io.karpfen.ide.core.lang.modelpath.pathSegmentsOf
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.navigation.ReferenceContributor
import io.karpfen.ide.core.lang.navigation.ReferenceScope
import io.karpfen.ide.core.lang.navigation.SearchFile

/**
 * The KStates part of cross-file references. For an event type these are the event names in `EVENT("d", "n")`. For a
 * member these are the model path segments that resolve to it. Such paths stand in actions, VALUE, IF IN SCOPE, macro
 * arguments and `$( … )`, and each is resolved in its scope by [scopes] and [paths]. For a member they are also the
 * relation arguments of `SETOBJ` and `APPENDOBJ` whose source path has the owner of the member as type.
 */
internal class KstatesSymbolReferences(
    private val scopes: KstatesPathScopes,
    private val paths: ModelPathResolver,
) : ReferenceContributor {

    override fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location> {
        if (file.language != KarpfenLanguage.KSTATES) return emptyList()
        return when (symbol) {
            is ContextSymbol.Type -> if (scope.isEventType(symbol.name)) {
                file.source.outcome.index.references(ReferenceKind.EVENT_NAME, symbol.name).map { file.source.location(it.range) }
            } else {
                emptyList()
            }
            is ContextSymbol.Member -> relationArguments(symbol, file, scope) + pathSegments(symbol, file, scope)
        }
    }

    /**
     * The relation arguments of `SETOBJ` and `APPENDOBJ` named like [symbol] whose source path has its owner as type.
     */
    private fun relationArguments(symbol: ContextSymbol.Member, file: SearchFile, scope: ReferenceScope): List<Location> =
        file.source.outcome.index.references(ReferenceKind.RELATION_KEY, symbol.name)
            .filter { reference ->
                val source = reference.qualifier ?: return@filter false
                paths.resolve(source, scopes.scopeIn(scope.request, file.source, reference.range.start).path).type?.name == symbol.owner
            }
            .map { file.source.location(it.range) }

    /** The model path segments of the file that resolve in their scope to the member [symbol]. */
    private fun pathSegments(symbol: ContextSymbol.Member, file: SearchFile, scope: ReferenceScope): List<Location> =
        file.source.outcome.index.referencesOf(ReferenceKind.MODEL_PATH)
            .filter { symbol.name in it.name }
            .flatMap { reference ->
                val resolution = paths.resolve(reference.name, scopes.scopeIn(scope.request, file.source, reference.range.start).path)
                val written = pathSegmentsOf(reference.name, reference.range, file.text)
                resolution.segments.withIndex()
                    .filter { (_, segment) ->
                        segment.member?.origin == PathOrigin.MEMBER && segment.name == symbol.name && segment.owner?.name == symbol.owner
                    }
                    .mapNotNull { (index, _) -> written.getOrNull(index)?.range?.let(file.source::location) }
            }
}
