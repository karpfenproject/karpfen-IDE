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
package io.karpfen.ide.core.document.environment

import kotlinx.serialization.Serializable

/**
 * An environment descriptor `*.kenv.json` as a typed value: which files form one runnable modeling context and how the
 * runtime runs them. File paths are relative to the descriptor's directory.
 *
 * This class is the one declaration of the descriptor's shape. Its property names are the JSON keys, its nesting is the
 * nesting of the objects, a property without a default value is a required key, a nullable property a key that may be
 * `null`, and the property types are the value kinds. The annotations add what the types cannot say: what each key
 * means ([KeyDoc], the text that hover and completion show, so the properties carry no `@property` tags of their own),
 * what its strings name ([NamesFile], [NamesObject], [NamesEventDomain]) and which values completion suggests
 * ([Suggests], [SuggestsNumbers]). [EnvironmentSchema.ROOT] is derived from the serial descriptor of this class, so a
 * new key is one property with its annotations.
 *
 * kotlinx.serialization decodes a descriptor into this class after the workspace checked its text against
 * [EnvironmentSchema.ROOT], and the file templates encode new descriptors from it, so a written descriptor reads back
 * without findings. Renaming a property renames a key of the format the runtime reads.
 */
@Serializable
public data class EnvironmentDescriptor(
    @KeyDoc("The domain metamodel of the environment: a .kmeta file, relative to this descriptor.")
    @NamesFile(DescriptorFileRole.METAMODEL)
    public val metamodel: String,

    @KeyDoc("The event metamodel that declares the events of the state machines, usually EVENTS.kmeta.")
    @NamesFile(DescriptorFileRole.EVENTS)
    public val events: String? = null,

    @KeyDoc("The model the runtime starts from: a .kmodel file of the domain metamodel, relative to this descriptor.")
    @NamesFile(DescriptorFileRole.MODEL)
    public val model: String,

    @KeyDoc("The state machines of the environment, each running attached to one object of the model.")
    public val stateMachines: List<StateMachineEntry> = emptyList(),

    @KeyDoc("The delay between two ticks of the runtime's engine, in milliseconds.")
    @SuggestsNumbers(50, 100, 500)
    public val tickDelayMs: Long? = null,

    @KeyDoc("How long an event stays available to the state machines, in milliseconds.")
    @SuggestsNumbers(1_000, 3_000, 10_000)
    public val eventTtlMs: Long? = null,

    @KeyDoc("What the IDE observes while the environment runs: objects and event domains.")
    public val observe: ObserveSpec? = null,

    @KeyDoc("How the runtime runs the environment: its container image and extra Python packages.")
    public val runtime: RuntimeSpec? = null,

    @KeyDoc(
        "Bridge configurations (.kbridge.json) that connect the environment to MQTT or ROS; the IDE does not interpret " +
            "them yet.",
    )
    @NamesFile(DescriptorFileRole.BRIDGE)
    public val bridges: List<String> = emptyList(),
) {
    /** One state machine of the environment: [file] runs attached to the object with the key [attachedTo]. */
    @Serializable
    public data class StateMachineEntry(
        @KeyDoc("The state machine: a .kstates file, relative to this descriptor.")
        @NamesFile(DescriptorFileRole.STATE_MACHINE)
        public val file: String,

        @KeyDoc(
            "The key of the model object the state machine runs attached to; its type should be the type the state " +
                "machine names after ATTACHED TO.",
        )
        @NamesObject
        public val attachedTo: String,
    )

    /** What the IDE observes while the environment runs: the objects matching [objects] and the event [domains]. */
    @Serializable
    public data class ObserveSpec(
        @KeyDoc(
            "The keys of the objects the IDE observes while the environment runs, as a pattern; " +
                "\"${EnvironmentSchema.EVERY_OBJECT}\" observes every object.",
        )
        @Suggests(EnvironmentSchema.EVERY_OBJECT)
        public val objects: String,

        @KeyDoc(
            "The event domains the IDE observes while the environment runs, the domains of EVENT(\"domain\", \"name\") " +
                "in the state machines.",
        )
        @NamesEventDomain
        public val domains: List<String> = emptyList(),
    )

    /** How the runtime runs the environment: in the container [image], with the extra [pythonPackages]. */
    @Serializable
    public data class RuntimeSpec(
        @KeyDoc("The container image the runtime runs in, such as ghcr.io/karpfenproject/karpfen-runtime:0.x.")
        public val image: String? = null,

        @KeyDoc("Extra Python packages the runtime installs for the EVAL blocks of the state machines, such as numpy.")
        @Suggests("numpy")
        public val pythonPackages: List<String> = emptyList(),
    )
}
