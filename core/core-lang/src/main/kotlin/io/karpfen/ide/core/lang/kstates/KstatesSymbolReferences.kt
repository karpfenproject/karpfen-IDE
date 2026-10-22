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
 * The KStates part of cross-file references: event names (`EVENT("d", "n")`) of an event type; for a member,
 * the model-path segments resolving to it (paths of actions, VALUE, IF IN SCOPE, macro arguments and `$( … )`, each
 * resolved in its scope by [scopes] and [paths]) and the relation arguments of `SETOBJ`/`APPENDOBJ` whose source path
 * has the member's owner as type.
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

    /** The `SETOBJ`/`APPENDOBJ` relation arguments named like [symbol] whose source path has its owner as type. */
    private fun relationArguments(symbol: ContextSymbol.Member, file: SearchFile, scope: ReferenceScope): List<Location> =
        file.source.outcome.index.references(ReferenceKind.RELATION_KEY, symbol.name)
            .filter { reference ->
                val source = reference.qualifier ?: return@filter false
                paths.resolve(source, scopes.scopeIn(scope.request, file.source, reference.range.start).path).type?.name == symbol.owner
            }
            .map { file.source.location(it.range) }

    /** The model-path segments of the file that resolve, in their scope, to the member [symbol]. */
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
