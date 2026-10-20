package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.navigation.ReferenceContributor
import io.karpfen.ide.core.lang.navigation.ReferenceScope
import io.karpfen.ide.core.lang.navigation.SearchFile

/**
 * The KModel part of cross-file member references: `prop("k")`, `has("k")` and `knows("k")` keys whose qualifier (the
 * enclosing object's type, `SymbolReference.qualifier`) is the member's owner.
 */
internal class KmodelKeyReferences : ReferenceContributor {
    override fun references(symbol: ContextSymbol, file: SearchFile, scope: ReferenceScope): List<Location> {
        if (symbol !is ContextSymbol.Member || file.language != KarpfenLanguage.KMODEL) return emptyList()
        val index = file.source.outcome.index
        return (index.references(ReferenceKind.PROPERTY_KEY, symbol.name) + index.references(ReferenceKind.RELATION_KEY, symbol.name))
            .filter { it.qualifier == symbol.owner }
            .map { file.source.location(it.range) }
    }
}
