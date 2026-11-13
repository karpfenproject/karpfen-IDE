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

import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.catalogue.RejectedUpload
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeText
import io.karpfen.ide.runtime.protocol.catalogue.UploadError
import io.karpfen.ide.runtime.protocol.catalogue.UploadRejection
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.session.plan.AttachedMachine
import io.karpfen.ide.runtime.session.plan.DeclarationLocations
import io.karpfen.ide.runtime.session.plan.DeployConfiguration
import io.karpfen.ide.runtime.session.plan.DeploymentPlan
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.plan.UploadRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.nio.file.Path
import kotlin.test.assertIs

/**
 * The doubles of one deployment test wired together. These are a scripted client, its environment and connection, a
 * scripted observation, a recording listener and a ledger, all on the virtual time of [scope].
 *
 * @param scope the test's scope. The connection runs in its background scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DeployRig(private val scope: TestScope) {
    /** What the doubles answer and what they received. */
    val script: DeployScript = DeployScript { scope.testScheduler.currentTime }

    /** The client double. */
    val client: ScriptedClient = ScriptedClient(script)

    /** The observation double. */
    val observation: ScriptedObservation = ScriptedObservation(script)

    /** The listener of the deployment. */
    val listener: RecordingListener = RecordingListener(script)

    /** The ledger the deployer records in. */
    val ledger: EnvironmentLedger = EnvironmentLedger()

    /** The request deploying [plan] with [options] through the doubles. */
    fun request(plan: DeploymentPlan = LampPlans.plan(), options: DeployOptions = DeployOptions()): DeployRequest =
        DeployRequest(plan, client, CLIENT, options, scope.backgroundScope, observation)

    /** Deploys [plan] with [options] and [timing], handing environments that stay to [disposer]. */
    suspend fun deploy(
        plan: DeploymentPlan = LampPlans.plan(),
        options: DeployOptions = DeployOptions(),
        timing: DeployTiming = DeployTiming(),
        disposer: EnvironmentDisposer = KeepEnvironments(ledger),
    ): DeploymentResult = Deployer(ledger, timing, disposer).deploy(request(plan, options), listener)
}

/**
 * Plans of a lamp with a wall switch, built without a workspace. They hold a metamodel, event definitions, a model with
 * the objects `lamp`, `bulb` and `spare`, and two machines attached to `lamp` and `wallSwitch`. Unless a test says
 * otherwise, the machines need the runtime features `History` and `TickLimit`.
 */
internal object LampPlans {
    /** The folder of the lamp's files. */
    val directory: Path = Path.of("/workspace/lamp").toAbsolutePath()

    /** The environment descriptor. */
    val descriptor: Path = directory.resolve("lamp.kenv.json")

    /** The model file. */
    val modelFile: Path = directory.resolve("lamp.kmodel")

    /** The text of the model, on one line. */
    const val MODEL_TEXT: String = "object lamp: Lamp; object bulb: Bulb; object spare: Bulb"

    /** The analyzed model text. */
    val modelSnapshot: TextSnapshot = TextSnapshot(MODEL_TEXT, 3)

    /** The metamodel upload. */
    val metamodel: PlannedUpload = upload(UploadRole.METAMODEL, "lamp.kmeta")

    /** The upload of the event definitions. */
    val events: PlannedUpload = upload(UploadRole.EVENTS, "lamp-events.kmeta")

    /** The model upload. */
    val model: PlannedUpload = PlannedUpload(UploadRole.MODEL, modelFile, modelSnapshot, null)

    /** The machine of the lamp. */
    val lampMachine: PlannedUpload = upload(UploadRole.STATE_MACHINE, "lamp.kstates", "lamp")

    /** The machine of the wall switch. */
    val switchMachine: PlannedUpload = upload(UploadRole.STATE_MACHINE, "switch.kstates", "wallSwitch")

    /** Where the model's objects are declared, which is at their keys in the model text. */
    val locations: DeclarationLocations = DeclarationLocations(
        objects = mapOf(modelFile to listOf("lamp", "bulb", "spare").associateWith { rangeOf(it) }),
        snapshots = mapOf(modelFile to modelSnapshot),
    )

    /**
     * The plan of the lamp with [configuration], [domains] and [features], with the event definitions if [withEvents].
     */
    fun plan(
        configuration: DeployConfiguration = DeployConfiguration(20, 1500),
        domains: List<String> = listOf("service", "switch"),
        withEvents: Boolean = true,
        features: List<FeatureName> = listOf(FeatureName.HISTORY, FeatureName.TICK_LIMIT),
    ): DeploymentPlan =
        DeploymentPlan.Builder()
            .name("lamp")
            .descriptor(descriptor)
            .generation(3)
            .uploads(listOfNotNull(metamodel, events.takeIf { withEvents }, model, lampMachine, switchMachine))
            .attachments(
                listOf(
                    AttachedMachine("lamp", lampMachine.file, "Lamp", "Lamp"),
                    AttachedMachine("wallSwitch", switchMachine.file, "Switch", "Switch"),
                ),
            )
            .configuration(configuration)
            .domains(domains)
            .features(features)
            .modelObjectIds(setOf("lamp", "bulb", "spare", "wallSwitch"))
            .locations(locations)
            .build()

    /** The upload of the file [name] with [role], its text naming the file. */
    private fun upload(role: UploadRole, name: String, attachedTo: String? = null): PlannedUpload =
        PlannedUpload(role, directory.resolve(name), TextSnapshot("text of $name", 1), attachedTo)

    /** The range of the first occurrence of the object [key] in the model text. */
    private fun rangeOf(key: String): TextRange {
        val start = MODEL_TEXT.indexOf("object $key:") + "object ".length
        return TextRange(start, start + key.length)
    }
}

/** Failures of the runtime as the client reports them. */
internal object Failures {
    /** The runtime could not be reached for [route]. */
    fun unreachable(route: RuntimeRoute): RuntimeFailure = RuntimeFailure.Unreachable(route, "Connection refused")

    /** The runtime did not answer [route] in time. */
    fun noAnswer(route: RuntimeRoute): RuntimeFailure = RuntimeFailure.NoAnswer(route, "Request timeout", timedOut = true)

    /** The runtime rejected [route] with [text] filled with [values]. */
    fun rejected(route: RuntimeRoute, text: RuntimeText, values: Map<String, String>, status: Int = 409): RuntimeFailure =
        RuntimeFailure.Rejected(route, status, text.kind, text.render(values), values)

    /** The runtime refused the text sent to [route] for its errors, answering with the body of [rejection]. */
    fun refused(route: RuntimeRoute, rejection: UploadRejection): RuntimeFailure =
        RuntimeFailure.Rejected(route, 400, RejectionKind.CONTENT_REJECTED, rejection.render(), emptyMap(), rejection)

    /** The runtime refused the [upload] sent to [route] for one error with [message] and [code] at its first character. */
    fun refused(route: RuntimeRoute, upload: RejectedUpload, message: String, code: String): RuntimeFailure =
        refused(route, UploadRejection(upload, listOf(UploadError(1, 1, message, code))))

    /** The runtime rejected [route] with [text], which is none of the texts the client knows. */
    fun unknownText(route: RuntimeRoute, text: String): RuntimeFailure =
        RuntimeFailure.Rejected(route, 400, RejectionKind.UNCLASSIFIED, text, emptyMap())

    /** The runtime failed with an internal error on [route]. */
    fun fault(route: RuntimeRoute, message: String): RuntimeFailure = RuntimeFailure.ServerFault(route, message)

    /** The runtime no longer knows the environment, answering [route]. */
    fun unknownEnvironment(route: RuntimeRoute): RuntimeFailure =
        rejected(route, RuntimeText.UNKNOWN_ENVIRONMENT, mapOf("env" to ENVIRONMENT.value), status = 400)
}

/** A snapshot of the lamp's environment in [phase] in which the machines of [activeStates] report their stacks. */
internal fun lampSnapshot(phase: RuntimePhase, activeStates: Map<String, List<String>>): RuntimeModelSnapshot =
    RuntimeModelSnapshot(ENVIRONMENT, phase, activeStates.size.toLong(), activeStates, emptyList())

/** The deployed environment of a successful result. It fails the test for any other result. */
internal fun DeploymentResult.deployed(): DeploymentResult.Deployed = assertIs<DeploymentResult.Deployed>(this, "expected a deployment: $this")

/** The failure of a failed result. It fails the test for any other result. */
internal fun DeploymentResult.failed(): DeploymentResult.Failed = assertIs<DeploymentResult.Failed>(this, "expected a failed deployment: $this")
