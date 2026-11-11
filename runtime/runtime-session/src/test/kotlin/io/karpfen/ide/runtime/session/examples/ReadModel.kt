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
package io.karpfen.ide.runtime.session.examples

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.fixtures.ExampleOrigin
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.fixtures.ModelWireText
import io.karpfen.ide.runtime.session.mirror.MirrorReducer
import io.karpfen.ide.runtime.session.mirror.MirrorSeeder
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.schema.TypeSchema

/**
 * A domain metamodel and a model read from their texts without a workspace, with the mirror's inputs derived from them
 * as the preparation of a deployment derives them.
 *
 * @property metamodel the domain metamodel.
 * @property model the model.
 */
class ReadModel(val metamodel: Metamodel, val model: Model) {
    /** The schema of the domain metamodel. */
    val schema: TypeSchema = TypeSchema.of(metamodel)

    /** The seed of the model. */
    val seed: MirrorSnapshot = MirrorSeeder.seed(model, schema)

    /** The model as the runtime writes it. */
    val wire: ModelWireText by lazy { ModelWireText(model) }

    /** A reducer whose model objects are those of the seed. */
    fun reducer(): MirrorReducer = MirrorReducer(schema, seed.objects.keys)

    /** The runtime's JSON of the object [id] of the model, read as the client reads it. */
    fun wireObject(id: String): WireValue.WireObject = WireTexts.wireObject(wire.objectJson(id))

    /** The roots of a snapshot of the model, read as the client reads them. */
    fun wireRoots(): List<WireValue.WireObject> = wire.rootsJson().map(WireTexts::wireObject)

    companion object {
        /** The model of [modelText] against the metamodel of [metamodelText]. */
        fun of(metamodelText: String, modelText: String): ReadModel {
            val metamodel = DslTexts.metamodel(metamodelText)
            return ReadModel(metamodel, DslTexts.model(modelText, metamodel))
        }

        /** The model of the runtime's example in [directory], which is one of [AnalyzedContexts.RUNTIME_EXAMPLES]. */
        fun runtimeExample(directory: String): ReadModel {
            val context = Examples.contexts().single { it.directory == "${ExampleOrigin.KARPFEN_RUNTIME.directoryName}/$directory" }
            return of(checkNotNull(context.metamodel).text, context.models.single().text)
        }

        /** The model of the synthesized [model]. */
        fun synthesized(model: SynthesizedModel): ReadModel {
            val files = SynthesizedModels.files(model)
            return of(SynthesizedModels.text(model, files.metamodel), SynthesizedModels.text(model, files.model))
        }
    }
}
