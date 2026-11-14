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
package io.karpfen.ide.runtime.session.deploy

import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.plan.UploadRole

/**
 * Uploads the metamodel, the event definitions or the model. It uploads the analyzed snapshot of the file.
 *
 * @param upload what to upload. Its role is never that of a state machine, because state machines have their own step.
 */
internal class UploadStep(private val upload: PlannedUpload) : DeployStep {
    init {
        require(upload.role != UploadRole.STATE_MACHINE && upload.role != UploadRole.DEFINITION) {
            "a state machine is uploaded by its own step"
        }
    }

    override val kind: DeployStepKind = when (upload.role) {
        UploadRole.METAMODEL -> DeployStepKind.UPLOAD_METAMODEL
        UploadRole.EVENTS -> DeployStepKind.UPLOAD_EVENTS
        else -> DeployStepKind.UPLOAD_MODEL
    }
    override val title: String = "Uploading ${upload.fileName}"

    override suspend fun execute(run: DeployRun): StepOutcome {
        val environment = run.createdEnvironment
        val text = upload.snapshot.text
        val outcome = when (upload.role) {
            UploadRole.METAMODEL -> environment.setMetamodel(text)
            UploadRole.EVENTS -> environment.setEventDefinitions(text)
            else -> environment.setModel(text)
        }
        return run.settle(outcome, upload)
    }
}

/**
 * Attaches one state machine to its object key. The order of the attachments becomes the runtime's tick order.
 *
 * @param upload the state machine.
 */
internal class AttachStep(private val upload: PlannedUpload) : DeployStep {
    /** The key the machine is attached to. */
    private val attachedTo: String = requireNotNull(upload.attachedTo) { "a state machine upload names its key" }

    override val kind: DeployStepKind = DeployStepKind.ATTACH_STATE_MACHINE
    override val title: String = "Attaching ${upload.fileName} to $attachedTo"

    override suspend fun execute(run: DeployRun): StepOutcome =
        run.settle(run.createdEnvironment.setStateMachine(attachedTo, upload.snapshot.text), upload)
}

/**
 * Uploads one attachable state machine without an object key, so that the environment holds it as a definition under
 * its name. The runtime checks at the activation that every type that behaves after a name has a definition of that
 * name, so the definitions are uploaded before the activation, in descriptor order with the attached state machines.
 *
 * @param upload the definition.
 */
internal class DefineStep(private val upload: PlannedUpload) : DeployStep {
    init {
        require(upload.role == UploadRole.DEFINITION) { "only a definition is uploaded without a key: ${upload.role}" }
    }

    override val kind: DeployStepKind = DeployStepKind.DEFINE_STATE_MACHINE
    override val title: String = "Uploading the definition ${upload.fileName}"

    override suspend fun execute(run: DeployRun): StepOutcome =
        run.settle(run.createdEnvironment.defineStateMachine(upload.snapshot.text), upload)
}

/**
 * Sets the tick delay of the deploy configuration. The step always runs, so the runtime's own default never applies.
 *
 * @param milliseconds the tick delay.
 */
internal class TickDelayStep(private val milliseconds: Int) : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.SET_TICK_DELAY
    override val title: String = "Setting the tick delay to $milliseconds ms"

    override suspend fun execute(run: DeployRun): StepOutcome = run.settle(run.createdEnvironment.setTickDelay(milliseconds))
}

/**
 * Sets the event time to live of the deploy configuration. The step always runs, so the runtime's own default never
 * applies.
 *
 * @param milliseconds the time to live.
 */
internal class EventTtlStep(private val milliseconds: Long) : DeployStep {
    override val kind: DeployStepKind = DeployStepKind.SET_EVENT_TTL
    override val title: String = "Setting the event time to live to $milliseconds ms"

    override suspend fun execute(run: DeployRun): StepOutcome = run.settle(run.createdEnvironment.setEventTtl(milliseconds))
}

/**
 * Registers a listener of the deployment's client for every event domain the state machines use. It reports each
 * domain as it goes and stops at the first registration that does not succeed.
 *
 * @param domains the domains, at least one.
 */
internal class ListenDomainsStep(private val domains: List<String>) : DeployStep {
    init {
        require(domains.isNotEmpty()) { "without domains the step is skipped" }
    }

    override val kind: DeployStepKind = DeployStepKind.LISTEN_DOMAINS
    override val title: String = "Listening to ${domains.size} event domain${if (domains.size == 1) "" else "s"}"

    override suspend fun execute(run: DeployRun): StepOutcome {
        domains.forEachIndexed { index, domain ->
            run.report("${index + 1} of ${domains.size}: $domain")
            val outcome = run.createdEnvironment.registerDomainListener(run.request.clientId, domain)
            if (outcome !is CallOutcome.Done) return run.settle(outcome)
        }
        return StepOutcome.Done(domains.joinToString())
    }
}

/**
 * Switches on the runtime features of the language features that are switched on for the project, so that the runtime
 * applies the words of the machines that only such a feature gives meaning to, and the features the run asks for, such
 * as `TrueParallel`. It runs before the activation, while the environment is configuring. It
 * reports each feature as it goes and stops at the first switch that does not succeed, which fails the deployment.
 *
 * @param features the features, at least one, in the order they are switched on.
 */
internal class SwitchFeaturesOnStep(private val features: List<FeatureName>) : DeployStep {
    init {
        require(features.isNotEmpty()) { "without features the step is skipped" }
    }

    override val kind: DeployStepKind = DeployStepKind.SWITCH_FEATURES_ON
    override val title: String = "Switching on ${features.joinToString()}"

    override suspend fun execute(run: DeployRun): StepOutcome {
        features.forEachIndexed { index, feature ->
            run.report("${index + 1} of ${features.size}: $feature")
            val outcome = run.createdEnvironment.activateFeature(feature)
            if (outcome !is CallOutcome.Done) return run.settle(outcome)
        }
        return StepOutcome.Done(features.joinToString())
    }
}

/**
 * A step this deployment does not need, shown as skipped with the reason.
 *
 * @property kind what the step would do.
 * @property title what it would concern.
 * @param reason why it is not needed.
 */
internal class SkippedStep(
    override val kind: DeployStepKind,
    override val title: String,
    private val reason: String,
) : DeployStep {
    override suspend fun execute(run: DeployRun): StepOutcome = StepOutcome.Skipped(reason)
}
