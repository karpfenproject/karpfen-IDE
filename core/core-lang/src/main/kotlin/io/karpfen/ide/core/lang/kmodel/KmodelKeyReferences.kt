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
package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.navigation.ContextSymbol
import io.karpfen.ide.core.lang.navigation.ReferenceContributor
import io.karpfen.ide.core.lang.navigation.ReferenceScope
import io.karpfen.ide.core.lang.navigation.SearchFile

/**
 * The KModel part of cross-file member references. These are `prop("k")`, `has("k")` and `knows("k")` keys whose
 * qualifier is the owner of the member. The qualifier is the type of the enclosing object, in
 * `SymbolReference.qualifier`.
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
