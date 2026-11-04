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
package io.karpfen.ide.runtime.protocol.conformance

import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.WireValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * The small karpfen models the scenarios run against a real runtime, each in a folder of its own with a descriptor
 * `<folder>.kenv.json` that names its files and attachments (and no tick delay or time to live: those come from the
 * deploy configuration, and [SynthesizedModels.copyTo] adds them when a scenario asks). Every model has one root object
 * and writes every scalar its machines set; no event domain equals an attached key. All but [DEPOT] run without Python.
 *
 * @property folder the folder of the model below [SynthesizedModels.RESOURCE_ROOT].
 */
public enum class SynthesizedModel(public val folder: String) {
    /**
     * A lamp in a living room with a bulb, a spare bulb and the wall switch it knows, and two machines, one on the lamp
     * and one on the switch, that both react to every `press` of the domain `switch`. It covers deploying, the
     * lifecycle, injected events with JSON and KModel payloads that decide a guard (`dim` with `bright`), forced states,
     * the deletion of a model object (`smash` of the domain `service` deletes `spare`), two machines, `knows` inlining
     * and text with spaces and an en dash on the wire. Both domains are declared by the events file.
     */
    LAMP("lamp"),

    /**
     * A metronome whose `go` of the domain `beat` runs one burst over several ticks, appending exactly `1, 2, 3, a, b`
     * (two parallel lanes, `a` first) to a list that only grows, and raising `burst done` in the domain `tick`, which
     * the events file does not declare. It covers observation, ordering, raised events and load.
     */
    METRONOME("metronome"),

    /**
     * A depot that creates crates at run time with Python (`receive` into its atomic `slot`, `stack` into its list),
     * relabels the crate in the slot and scraps it without Python, all in the domain `dock`. The one model that needs
     * Python, for objects created at run time; its `label` and `scrap` serve as Python-free reactions.
     */
    DEPOT("depot"),

    /**
     * Two nodes that know each other, a reference loop, with a machine on `a` that sets a scalar on entry. The runtime
     * refuses to write the model as JSON and the IDE refuses to deploy it, so it is uploaded through raw requests only.
     */
    RING("ring"),
    ;

    /** The file name of the model's descriptor. */
    public val descriptorName: String
        get() = "$folder.kenv.json"
}

/**
 * The files a descriptor of a synthesized model names, in the order a deployment uploads them.
 *
 * @property metamodel the domain metamodel.
 * @property events the event definitions, or null if the model has none.
 * @property model the model.
 * @property machines the state machines with their attachments, in descriptor order.
 */
public data class ModelFiles(
    public val metamodel: String,
    public val events: String?,
    public val model: String,
    public val machines: List<AttachedMachineFile>,
) {
    /** Every named file: metamodel, events, model, then the machines. */
    public val all: List<String>
        get() = listOfNotNull(metamodel, events, model) + machines.map { it.file }
}

/**
 * A state machine file of a synthesized model and the object key it is attached to.
 *
 * @property file the file name of the state machine.
 * @property attachedTo the key of the object it runs attached to.
 */
public data class AttachedMachineFile(public val file: String, public val attachedTo: String)

/**
 * The timing a copied descriptor states, the deploy configuration of a scenario; a null value leaves its key out, so
 * the descriptor format's default applies.
 *
 * @property tickDelayMs the `tickDelayMs` of the descriptor, or null for none.
 * @property eventTtlMs the `eventTtlMs` of the descriptor, or null for none.
 */
public data class DescriptorTiming(public val tickDelayMs: Long? = null, public val eventTtlMs: Long? = null) {
    public companion object {
        /** No timing keys: the defaults of the descriptor format apply. */
        public val NONE: DescriptorTiming = DescriptorTiming()

        /**
         * The timing of scenarios that inject events: a tick delay of 50 ms and a time to live of 30 000 ms, so that a
         * slow first tick of a real runtime cannot expire an event.
         */
        public val EVENT_SCENARIOS: DescriptorTiming = DescriptorTiming(tickDelayMs = 50, eventTtlMs = 30_000)
    }
}

/**
 * The texts of the [SynthesizedModel]s and of the failure inputs, as test-fixture resources below [RESOURCE_ROOT], and
 * copies of them on disk: a deployment uploads saved files, so every scenario that deploys writes a model into a
 * folder of its own with [copyTo] and opens it as a workspace. The models are IDE-authored and Apache 2.0 licensed; the
 * README of the folder describes them.
 */
public object SynthesizedModels {
    /** The classpath folder of the models, one sub-folder per [SynthesizedModel.folder]. */
    public const val RESOURCE_ROOT: String = "io/karpfen/ide/runtime/protocol/conformance/models"

    /**
     * A failure input next to the lamp: the lamp's model with `spare` typed `Bulbb`, which the runtime's parser refuses
     * with 400 `Unknown class type 'Bulbb' for object 'spare'`. Uploaded raw; never copied by [copyTo].
     */
    public const val LAMP_UNKNOWN_TYPE: String = "lamp-unknown-type.kmodel"

    /** The failure inputs of [model], which its folder holds besides the files its descriptor names. */
    public fun failureInputs(model: SynthesizedModel): List<String> =
        if (model == SynthesizedModel.LAMP) listOf(LAMP_UNKNOWN_TYPE) else emptyList()

    /** The text of the descriptor of [model], exactly as the resource holds it. */
    public fun descriptorText(model: SynthesizedModel): String = text(model, model.descriptorName)

    /**
     * The exact text of the file [fileName] of [model]'s folder.
     *
     * @throws IllegalArgumentException if the folder has no such file.
     */
    public fun text(model: SynthesizedModel, fileName: String): String {
        val resource = "$RESOURCE_ROOT/${model.folder}/$fileName"
        val stream = requireNotNull(SynthesizedModels::class.java.classLoader.getResourceAsStream(resource)) {
            "test-fixture resource '$resource' not found on the classpath"
        }
        return stream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    /**
     * The files that the descriptor of [model] names, read from the descriptor.
     *
     * @throws IllegalStateException if the descriptor is not a descriptor of the expected shape.
     */
    public fun files(model: SynthesizedModel): ModelFiles {
        val descriptor = when (val reading = RuntimeJson.read(descriptorText(model))) {
            is JsonReading.Read -> reading.value as? WireValue.WireObject
            is JsonReading.Malformed -> null
        } ?: error("the descriptor of ${model.folder} is not a JSON object")
        fun string(owner: WireValue.WireObject, key: String): String? = (owner[key] as? WireValue.WireString)?.value
        val machines = (descriptor["stateMachines"] as? WireValue.WireArray)?.items.orEmpty().map { entry ->
            val machine = entry as? WireValue.WireObject ?: error("a state machine entry of ${model.folder} is not an object")
            AttachedMachineFile(
                file = string(machine, "file") ?: error("a state machine of ${model.folder} names no file"),
                attachedTo = string(machine, "attachedTo") ?: error("a state machine of ${model.folder} is attached to nothing"),
            )
        }
        return ModelFiles(
            metamodel = string(descriptor, "metamodel") ?: error("the descriptor of ${model.folder} names no metamodel"),
            events = string(descriptor, "events"),
            model = string(descriptor, "model") ?: error("the descriptor of ${model.folder} names no model"),
            machines = machines,
        )
    }

    /**
     * Writes the descriptor of [model] and the files it names into [directory], which is created if needed, and answers
     * the path of the copied descriptor. The descriptor gains the keys of [timing] that are set; every other file is
     * written exactly as the resource holds it. Failure inputs are not copied.
     */
    public fun copyTo(directory: Path, model: SynthesizedModel, timing: DescriptorTiming = DescriptorTiming.NONE): Path {
        Files.createDirectories(directory)
        files(model).all.forEach { name -> Files.writeString(directory.resolve(name), text(model, name)) }
        val descriptor = directory.resolve(model.descriptorName)
        Files.writeString(descriptor, withTiming(descriptorText(model), timing))
        return descriptor
    }

    /** The descriptor [text] with the keys of [timing] that are set added as its last members. */
    private fun withTiming(text: String, timing: DescriptorTiming): String {
        val members = listOfNotNull(
            timing.tickDelayMs?.let { "  \"tickDelayMs\": $it" },
            timing.eventTtlMs?.let { "  \"eventTtlMs\": $it" },
        )
        if (members.isEmpty()) return text
        val end = text.lastIndexOf('}')
        check(end >= 0) { "a descriptor ends with '}'" }
        return text.substring(0, end).trimEnd() + ",\n" + members.joinToString(",\n") + "\n}\n"
    }
}
