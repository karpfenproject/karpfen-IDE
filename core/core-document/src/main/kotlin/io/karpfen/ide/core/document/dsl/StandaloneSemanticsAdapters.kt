package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.DslModel
import io.karpfen.ide.core.document.parse.StandaloneResult
import io.karpfen.ide.core.document.parse.StandaloneSemantics
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Standalone semantics of a `.kmeta` file: [DslReader.kmeta] without base types, its model, partial or complete,
 * wrapped as `DslModel.Kmeta`.
 */
internal class KmetaStandaloneSemantics(private val reader: DslReader) : StandaloneSemantics {
    override fun convert(snapshot: TextSnapshot): StandaloneResult {
        val read = reader.kmeta(snapshot)
        return StandaloneResult(read.model?.let(DslModel::Kmeta), read.diagnostics)
    }
}

/**
 * Standalone semantics of a `.kstates` file: [DslReader.kstates], its model, partial or complete, wrapped as
 * `DslModel.Kstates`.
 */
internal class KstatesStandaloneSemantics(private val reader: DslReader) : StandaloneSemantics {
    override fun convert(snapshot: TextSnapshot): StandaloneResult {
        val read = reader.kstates(snapshot)
        return StandaloneResult(read.model?.let(DslModel::Kstates), read.diagnostics)
    }
}
