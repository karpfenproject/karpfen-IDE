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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.problem.DeployProblem
import java.nio.file.Path

/**
 * Everything a deployment of one environment descriptor uploads, sets and registers, read from one analysis of the
 * saved workspace. Immutable, and free of dsl-tools objects, so it may cross threads.
 *
 * A plan comes from a [DeploymentPlanner] or, in tests, from its [Builder], which checks the plan's invariants.
 *
 * @property name the name of the context, which is the descriptor's file name without its suffix.
 * @property descriptor the path of the environment descriptor.
 * @property generation the generation of the analysis the plan was read from.
 * @property uploads the texts to upload, in upload order. First the metamodel, then the event definitions if there
 *   are any, then the model, and then the state machines in descriptor order. That order becomes the runtime's tick
 *   order.
 * @property attachments the state machines and what they are attached to, in descriptor order.
 * @property configuration the tick delay and the event time to live the deployment sends.
 * @property domains the event domains the state machines use in conditions and actions, sorted and distinct. The
 *   deployment registers a listener for each.
 * @property modelObjectIds the keys of the model's objects in the runtime's index of the model.
 * @property locations where the objects, types and states of the uploaded files are declared.
 * @property findings the warnings and infos of the checks. A plan holds no ERROR.
 */
public class DeploymentPlan private constructor(
    public val name: String,
    public val descriptor: Path,
    public val generation: Long,
    public val uploads: List<PlannedUpload>,
    public val attachments: List<AttachedMachine>,
    public val configuration: DeployConfiguration,
    public val domains: List<String>,
    public val modelObjectIds: Set<String>,
    public val locations: DeclarationLocations,
    public val findings: List<DeployProblem>,
) {
    /** The metamodel upload, which is always the first one. */
    public val metamodel: PlannedUpload
        get() = uploads.first()

    /** The upload of the event definitions, or null if the context has none. */
    public val events: PlannedUpload?
        get() = uploads.firstOrNull { it.role == UploadRole.EVENTS }

    /** The model upload. */
    public val model: PlannedUpload
        get() = uploads.single { it.role == UploadRole.MODEL }

    /** The state machine uploads, in descriptor order. */
    public val machines: List<PlannedUpload>
        get() = uploads.filter { it.role == UploadRole.STATE_MACHINE }

    override fun toString(): String =
        "DeploymentPlan(name=$name, generation=$generation, uploads=${uploads.map { it.fileName }}, " +
            "attachments=${attachments.map { it.attachedTo }}, configuration=$configuration, domains=$domains)"

    /**
     * Builds a [DeploymentPlan], with one setter per property, and checks its invariants in [build]. Not thread-safe.
     * A builder is used by one caller.
     */
    public class Builder {
        /** The plan's name. */
        private var name: String? = null

        /** The plan's descriptor. */
        private var descriptor: Path? = null

        /** The plan's analysis generation. */
        private var generation: Long = 0

        /** The plan's uploads. */
        private var uploads: List<PlannedUpload> = emptyList()

        /** The plan's attachments. */
        private var attachments: List<AttachedMachine> = emptyList()

        /** The plan's configuration. */
        private var configuration: DeployConfiguration = DeployConfiguration.DEFAULT

        /** The plan's event domains. */
        private var domains: List<String> = emptyList()

        /** The plan's object keys. */
        private var modelObjectIds: Set<String> = emptySet()

        /** The plan's declaration locations. */
        private var locations: DeclarationLocations = DeclarationLocations.EMPTY

        /** The plan's findings. */
        private var findings: List<DeployProblem> = emptyList()

        /** Sets the name of the context. Required. */
        public fun name(name: String): Builder = apply { this.name = name }

        /** Sets the descriptor's path. Required. */
        public fun descriptor(descriptor: Path): Builder = apply { this.descriptor = descriptor }

        /** Sets the generation of the analysis. It is 0 unless set. */
        public fun generation(generation: Long): Builder = apply { this.generation = generation }

        /** Sets the uploads, in upload order. Required. */
        public fun uploads(uploads: List<PlannedUpload>): Builder = apply { this.uploads = uploads.toList() }

        /** Sets the attachments, in descriptor order. There are none unless set. */
        public fun attachments(attachments: List<AttachedMachine>): Builder = apply { this.attachments = attachments.toList() }

        /** Sets the deploy configuration. It is the defaults of the descriptor format unless set. */
        public fun configuration(configuration: DeployConfiguration): Builder = apply { this.configuration = configuration }

        /** Sets the event domains. There are none unless set. */
        public fun domains(domains: List<String>): Builder = apply { this.domains = domains.toList() }

        /** Sets the keys of the model's objects. There are none unless set. */
        public fun modelObjectIds(modelObjectIds: Set<String>): Builder = apply { this.modelObjectIds = modelObjectIds.toSet() }

        /** Sets the declaration locations. There are none unless set. */
        public fun locations(locations: DeclarationLocations): Builder = apply { this.locations = locations }

        /** Sets the warnings and infos of the checks. There are none unless set. */
        public fun findings(findings: List<DeployProblem>): Builder = apply { this.findings = findings.toList() }

        /**
         * The plan.
         *
         * @throws IllegalArgumentException if the name or the descriptor is missing. It is also thrown if the uploads
         *   do not have the expected shape. That shape is exactly one metamodel upload first, at most one events upload
         *   right after it, exactly one model upload after both, and then the state machine uploads with distinct keys.
         *   The state machine uploads match [attachments] in order by key and file. A blank or repeated domain and a
         *   finding that is an error throw it as well.
         */
        public fun build(): DeploymentPlan {
            val name = requireNotNull(name) { "a plan needs the name of its context" }
            val descriptor = requireNotNull(descriptor) { "a plan needs its descriptor" }
            checkUploads()
            require(domains.none { it.isBlank() }) { "an event domain is never blank: $domains" }
            require(domains.toSet().size == domains.size) { "each event domain is listed once: $domains" }
            require(findings.none { it.severity == Severity.ERROR }) { "a plan holds no ERROR: $findings" }
            return DeploymentPlan(
                name, descriptor, generation, uploads, attachments, configuration, domains, modelObjectIds, locations,
                findings,
            )
        }

        /** Checks the order of the uploads and that the state machine uploads match the attachments. */
        private fun checkUploads() {
            val roles = uploads.map { it.role }
            require(roles.firstOrNull() == UploadRole.METAMODEL && roles.count { it == UploadRole.METAMODEL } == 1) {
                "a plan uploads exactly one metamodel, first: $roles"
            }
            require(roles.count { it == UploadRole.EVENTS } <= 1) { "a plan uploads at most one set of event definitions: $roles" }
            require(UploadRole.EVENTS !in roles || roles[1] == UploadRole.EVENTS) {
                "the event definitions follow the metamodel: $roles"
            }
            val modelIndex = if (UploadRole.EVENTS in roles) 2 else 1
            require(roles.getOrNull(modelIndex) == UploadRole.MODEL && roles.count { it == UploadRole.MODEL } == 1) {
                "a plan uploads exactly one model, after the metamodel and the event definitions: $roles"
            }
            val machines = uploads.drop(modelIndex + 1)
            require(machines.all { it.role == UploadRole.STATE_MACHINE }) { "only state machines follow the model: $roles" }
            val keys = machines.map { it.attachedTo }
            require(keys.toSet().size == keys.size) { "each object key has at most one state machine: $keys" }
            require(machines.map { it.attachedTo to it.file } == attachments.map { it.attachedTo to it.file }) {
                "the state machine uploads match the attachments in order: $keys against ${attachments.map { it.attachedTo }}"
            }
        }
    }
}
