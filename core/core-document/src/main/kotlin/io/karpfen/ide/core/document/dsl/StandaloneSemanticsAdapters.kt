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
