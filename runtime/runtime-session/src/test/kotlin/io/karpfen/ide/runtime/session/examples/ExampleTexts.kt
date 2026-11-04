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
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireValue

/** Reads small karpfen texts written in a test with the dsl-tools reader adapter, as the project analysis reads files. */
object DslTexts {
    /** The metamodel of [text], whose types may refer to [baseTypes]; the text must read without errors. */
    fun metamodel(text: String, baseTypes: List<ClassType> = emptyList()): Metamodel = clean(DslReader().kmeta(TextSnapshot(text, 0), baseTypes))

    /** The model of [text] against [metamodel]; the text must read without errors. */
    fun model(text: String, metamodel: Metamodel): Model = clean(DslReader().kmodel(TextSnapshot(text, 0), metamodel))

    /** The model of [text] against [metamodel] as far as it reads, also with errors such as unresolved references. */
    fun partialModel(text: String, metamodel: Metamodel): Model =
        checkNotNull(DslReader().kmodel(TextSnapshot(text, 0), metamodel).model) { "the text yields no model" }

    /** The state machine of [text]; the text must read without errors. */
    fun machine(text: String): StateMachine = clean(DslReader().kstates(TextSnapshot(text, 0)))

    /** The model of [result], which must have no errors. */
    private fun <T : Any> clean(result: ReadResult<T>): T {
        check(!result.hasErrors) { "the text does not read cleanly: ${result.diagnostics}" }
        return checkNotNull(result.model)
    }
}

/** Builds the runtime's messages from JSON texts written in a test, as the frame decoder would hand them over. */
object WireTexts {
    /** The object [text] holds, read as the client reads the runtime's JSON. */
    fun wireObject(text: String): WireValue.WireObject =
        when (val reading = RuntimeJson.read(text)) {
            is JsonReading.Read -> reading.value as? WireValue.WireObject ?: error("not a JSON object: $text")
            is JsonReading.Malformed -> error("malformed JSON at ${reading.offset}: ${reading.reason}")
        }

    /** The `objectChanged` push of [objectId] at [revision] with the object [value]. */
    fun changed(objectId: String, revision: Long, value: String): RuntimePush.ObjectChanged =
        RuntimePush.ObjectChanged(objectId, revision, wireObject(value))

    /** The `objectDeleted` push of [objectId] at [revision]. */
    fun deleted(objectId: String, revision: Long): RuntimePush.ObjectDeleted = RuntimePush.ObjectDeleted(objectId, revision)
}
