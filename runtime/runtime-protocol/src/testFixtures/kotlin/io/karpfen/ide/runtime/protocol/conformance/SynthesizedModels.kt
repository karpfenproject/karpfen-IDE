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
 * The small karpfen models that the scenarios run against a real runtime. They were written for the IDE's tests and
 * are licensed under the Apache License 2.0, like the rest of the repository. [SynthesizedModels] reads them.
 *
 * Each model lives in a folder of its own below [SynthesizedModels.RESOURCE_ROOT]. The folder holds a descriptor
 * `<folder>.kenv.json` that names only the files and the attachments. It names no tick delay and no time to live,
 * because those come from the deploy configuration. [SynthesizedModels.copyTo] adds them when a scenario asks. The
 * files carry no comments, so the texts stay exactly what is uploaded.
 *
 * Every model follows the same rules. It has one root object. Its model gives a value to every scalar that its machines
 * set. It uses no event domain that is named like an attached object key. All models except [DEPOT] run without
 * Python.
 *
 * Each entry lists the model's files, its machines and its event domains, and says what the model exercises.
 * `SynthesizedModelsTest` checks the rules. Only the depot mentions `EVAL` or `MACRO`, and only in its two creating
 * actions. Every descriptor names files that exist and names no timing. The descriptors attach the machines the
 * scenarios rely on. No event domain is named like an attached object key.
 *
 * @property folder the folder of the model below [SynthesizedModels.RESOURCE_ROOT].
 */
public enum class SynthesizedModel(public val folder: String) {
    /**
     * A lamp in a living room with a bulb, a spare bulb and the wall switch it knows. Two machines, one on the lamp and
     * one on the switch, both react to every `press` of the domain `switch`.
     *
     * The folder holds `lamp.kmeta`, `lamp-events.kmeta`, `lamp.kmodel`, `lamp.kstates` and `switch.kstates`.
     * `lamp.kstates` is attached to `lamp`, and `switch.kstates` is attached to `wallSwitch`. The event domains are
     * `switch` with `press` and `dim`, and `service` with `smash`. The events file declares every event of both
     * domains.
     *
     * The model covers deploying and the lifecycle. It covers injected events with JSON and KModel payloads that decide
     * a guard, which is `dim` with `bright`. It covers forced states and the deletion of a model object, because
     * `smash` of the domain `service` deletes `spare`. It also covers two machines that react to one event, `knows`
     * inlining, and text with spaces and an en dash on the wire.
     *
     * The folder also holds the failure input [SynthesizedModels.LAMP_UNKNOWN_TYPE].
     */
    LAMP("lamp"),

    /**
     * A metronome whose `go` of the domain `beat` runs one burst over several ticks. The burst appends exactly
     * `1, 2, 3, a, b` to a list that only grows. `a` and `b` come from two parallel lanes, and `a` comes first. At the
     * end the machine raises `burst done` in the domain `tick`, which the events file does not declare.
     *
     * The folder holds `metronome.kmeta`, `metronome-events.kmeta`, `metronome.kmodel` and `metronome.kstates`.
     * `metronome.kstates` is attached to `metronome`. The event domains are `beat` with `go`, and `tick` with the
     * raised `burst done`.
     *
     * The model covers continuous change in bursts over several ticks, a parallel split and join, observation,
     * ordering, raised events and load. The list that only grows shows lost or misordered updates.
     */
    METRONOME("metronome"),

    /**
     * A depot that creates crates at run time with Python. `receive` creates a crate in its atomic `slot`, and `stack`
     * appends one to its list. Without Python, `label` relabels the crate in the slot and `scrap` scraps it.
     *
     * The folder holds `depot.kmeta`, `depot-events.kmeta`, `depot.kmodel` and `depot.kstates`. `depot.kstates` is
     * attached to `depot`. The event domain is `dock` with `receive`, `stack`, `label` and `scrap`.
     *
     * It is the only model that needs Python. It uses `EVAL` in its two creating actions, for objects created at run
     * time and for their own changes and deletion. Its `label` and `scrap` serve as reactions that need no Python.
     */
    DEPOT("depot"),

    /**
     * Two nodes that know each other and so form a reference loop. A machine on `a` sets a scalar on entry. The runtime
     * cannot write the model as JSON, and the IDE refuses to deploy it. So it is uploaded through raw requests only.
     *
     * The folder holds `ring.kmeta`, `ring.kmodel` and `ring.kstates`, and no events file. `ring.kstates` is attached
     * to `a`. The model uses no event domain.
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
    /** Every named file, in the order metamodel, events, model and then the machines. */
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
 * The timing that a copied descriptor states, which is the deploy configuration of a scenario. A null value leaves its
 * key out, so the default of the descriptor format applies.
 *
 * @property tickDelayMs the `tickDelayMs` of the descriptor, or null for none.
 * @property eventTtlMs the `eventTtlMs` of the descriptor, or null for none.
 */
public data class DescriptorTiming(public val tickDelayMs: Long? = null, public val eventTtlMs: Long? = null) {
    public companion object {
        /** No timing keys, so the defaults of the descriptor format apply. */
        public val NONE: DescriptorTiming = DescriptorTiming()

        /**
         * The timing of scenarios that inject events. It has a tick delay of 50 ms and a time to live of 30 000 ms, so
         * that a slow first tick of a real runtime cannot expire an event.
         */
        public val EVENT_SCENARIOS: DescriptorTiming = DescriptorTiming(tickDelayMs = 50, eventTtlMs = 30_000)
    }
}

/**
 * The texts of the [SynthesizedModel]s and of the failure inputs, as test-fixture resources below [RESOURCE_ROOT], and
 * copies of them on disk. A deployment uploads saved files. So every scenario that deploys writes a model into a folder
 * of its own with [copyTo] and opens it as a workspace. [copyTo] can add a tick delay and an event time to live to the
 * copied descriptor.
 *
 * Failure inputs lie next to the models in the same folders. They are never copied with a model. The only one is
 * [LAMP_UNKNOWN_TYPE].
 */
public object SynthesizedModels {
    /** The classpath folder of the models, one sub-folder per [SynthesizedModel.folder]. */
    public const val RESOURCE_ROOT: String = "io/karpfen/ide/runtime/protocol/conformance/models"

    /**
     * A failure input in the lamp's folder. It is the lamp's model with the spare bulb `spare` typed `Bulbb`, which the
     * runtime's parser refuses with 400 `Unknown class type 'Bulbb' for object 'spare'`. Otherwise it equals
     * `lamp.kmodel`. Scenarios upload it raw, and [copyTo] never copies it.
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
     * Writes the descriptor of [model] and the files it names into [directory], which is created if needed. Returns
     * the path of the copied descriptor. The descriptor gains the keys of [timing] that are set, as its last members.
     * Every other file is written exactly as the resource holds it. Failure inputs are not copied.
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
