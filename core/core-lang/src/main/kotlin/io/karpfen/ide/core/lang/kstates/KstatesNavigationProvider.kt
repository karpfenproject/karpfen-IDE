package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * KStates navigation (spec §5): state references ↔ state declarations; macro references ↔ macro declarations;
 * `ATTACHED TO` and TAKES/RETURNS types → TYPE declaration in the context's metamodel ([types]); model-path segments →
 * member declarations in the metamodel ([paths] in the scope of [scopes], `PathMember.declaration`). Declarations known
 * only from the last good parse are returned with that parse's version and the document's lineage (see `Location`).
 */
internal class KstatesNavigationProvider(
    private val paths: ModelPathResolver,
    private val types: TypeCatalog,
    private val scopes: KstatesPathScopes,
) : NavigationProvider {
    override fun definition(request: FeatureRequest, offset: Int): List<Location> = TODO("WP-LANG: KStates definition")

    override fun references(request: FeatureRequest, offset: Int): List<Location> = TODO("WP-LANG: KStates references")
}
