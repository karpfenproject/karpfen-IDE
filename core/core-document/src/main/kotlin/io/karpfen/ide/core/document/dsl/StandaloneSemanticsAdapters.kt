package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.StandaloneResult
import io.karpfen.ide.core.document.parse.StandaloneSemantics
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextSnapshot

/** Standalone semantics of a `.kmeta` file: [DslConverter.kmeta] without base types, wrapped as `DslModel.Kmeta`. */
internal class KmetaStandaloneSemantics(private val converter: DslConverter) : StandaloneSemantics {
    override fun convert(snapshot: TextSnapshot, index: SyntaxIndex): StandaloneResult =
        TODO("WP-PARSE: convert KMeta standalone")
}

/** Standalone semantics of a `.kstates` file: [DslConverter.kstates], wrapped as `DslModel.Kstates`. */
internal class KstatesStandaloneSemantics(private val converter: DslConverter) : StandaloneSemantics {
    override fun convert(snapshot: TextSnapshot, index: SyntaxIndex): StandaloneResult =
        TODO("WP-PARSE: convert KStates standalone")
}
