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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.fake.FakeResponse
import io.karpfen.ide.runtime.session.deploy.CleanupReport
import io.karpfen.ide.runtime.session.deploy.DeployFailure
import io.karpfen.ide.runtime.session.deploy.DeployOptions
import io.karpfen.ide.runtime.session.deploy.DeployProgress
import io.karpfen.ide.runtime.session.deploy.DeployRequest
import io.karpfen.ide.runtime.session.deploy.DeployStepKind
import io.karpfen.ide.runtime.session.deploy.DeployTiming
import io.karpfen.ide.runtime.session.deploy.Deployer
import io.karpfen.ide.runtime.session.deploy.DeploymentResult
import io.karpfen.ide.runtime.session.deploy.EnvironmentLedger
import io.karpfen.ide.runtime.session.deploy.LedgerDisposition
import io.karpfen.ide.runtime.session.deploy.LedgerEntry
import io.karpfen.ide.runtime.session.deploy.StepProgress
import io.karpfen.ide.runtime.session.deploy.StepStatus
import io.karpfen.ide.runtime.session.events.EventCatalog
import io.karpfen.ide.runtime.session.events.EventDomain
import io.karpfen.ide.runtime.session.events.EventRef
import io.karpfen.ide.runtime.session.events.EventSources
import io.karpfen.ide.runtime.session.events.InjectableEvent
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.runtime.session.events.PayloadValidation
import io.karpfen.ide.runtime.session.mirror.MirrorChange
import io.karpfen.ide.runtime.session.mirror.MirrorObject
import io.karpfen.ide.runtime.session.mirror.MirrorProperty
import io.karpfen.ide.runtime.session.mirror.MirrorRelation
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.ObjectDifference
import io.karpfen.ide.runtime.session.observation.ModelUpdate
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.observation.SyncStatus
import io.karpfen.ide.runtime.session.plan.AttachedMachine
import io.karpfen.ide.runtime.session.plan.DeployConfiguration
import io.karpfen.ide.runtime.session.plan.DeploymentPlanner
import io.karpfen.ide.runtime.session.plan.PlanResult
import io.karpfen.ide.runtime.session.plan.PlannedUpload
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import io.karpfen.ide.runtime.session.problem.SourceLocation
import io.karpfen.ide.runtime.session.schema.PropertyShape
import io.karpfen.ide.runtime.session.schema.RelationShape
import io.karpfen.ide.runtime.session.schema.TypeShape
import io.karpfen.ide.runtime.session.states.ActiveStates
import io.karpfen.ide.runtime.session.states.MachineActivity
import io.karpfen.ide.runtime.session.states.MachineLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Contract test of the runtime session's public API: the public values are immutable and compared by value, the
 * options have their documented defaults and refuse what makes no sense, and the factory wires what it is given: the
 * planner prepares, the deployer deploys with the session's client id, plan, options and scope, the ledger records, and
 * a launched session starts deploying at once with a client id from the options' random and the prepared catalog.
 */
class RuntimeSessionContractTest {

    @TempDir
    lateinit var root: Path

    /** The public value classes of the session: every value that crosses threads. */
    private val publicValues: List<Class<*>> = listOf(
        PreparedDeployment::class.java, Preparation.Ready::class.java, Preparation.Blocked::class.java,
        RuntimeSessionOptions::class.java, SessionState.Ready::class.java, SessionState.Running::class.java,
        SessionState.Halted::class.java, SessionState.Stopping::class.java, SessionState.Stopped::class.java,
        SessionState.Failed::class.java, SessionCommandOutcome.Refused::class.java, SessionCommandOutcome.Failed::class.java,
        ObservedDomainEvent::class.java, SessionActivity.StepFinished::class.java, SessionActivity.CallFailed::class.java,
        SessionActivity.EventSent::class.java, SessionActivity.PushApplied::class.java, SessionActivity.DomainEventReceived::class.java,
        SessionActivity.Synchronized::class.java, SessionActivity.Anomaly::class.java, SessionActivity.ConnectionChanged::class.java,
        SessionActivity.ProblemRaised::class.java, SessionActivity.Note::class.java, DeployProblem::class.java,
        SourceLocation::class.java, PlannedUpload::class.java, AttachedMachine::class.java, DeployConfiguration::class.java,
        PlanResult.Ready::class.java, PlanResult.Blocked::class.java, DeployOptions::class.java, DeployTiming::class.java,
        StepProgress::class.java, DeployProgress::class.java, DeployFailure::class.java, CleanupReport::class.java,
        LedgerEntry::class.java, DeploymentResult.Deployed::class.java, DeploymentResult.Failed::class.java,
        PropertyShape::class.java, RelationShape::class.java, TypeShape::class.java, MirrorProperty::class.java,
        MirrorRelation::class.java, MirrorObject::class.java, MirrorSnapshot::class.java, MirrorChange::class.java,
        ObjectDifference::class.java, MachineLayout::class.java, MachineActivity::class.java, ActiveStates::class.java,
        ObservedModel::class.java, ModelUpdate::class.java, SyncStatus.Live::class.java, EventRef::class.java,
        InjectableEvent::class.java, EventDomain::class.java, EventSources::class.java, EventCatalog::class.java,
        InjectionRequest::class.java, InjectionOutcome.Sent::class.java, InjectionAvailability.Unavailable::class.java,
        PayloadValidation::class.java,
    )

    @Test
    fun `the public values are immutable and compared by value`() {
        val mutable = publicValues.flatMap { value ->
            value.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || Modifier.isFinal(it.modifiers) }.map { "${value.name}.${it.name}" }
        }
        assertEquals(emptyList(), mutable)
        val problem = DeployProblem(Severity.WARNING, DeployProblemCodes.ENGINE_SILENT, "silent", null)
        assertEquals(SessionCommandOutcome.Failed(problem), SessionCommandOutcome.Failed(problem.copy()))
        assertEquals(SessionActivity.Note("a note", 2.seconds), SessionActivity.Note("a note", 2.seconds))
    }

    @Test
    fun `the options have their documented defaults and refuse what makes no sense`() {
        val options = RuntimeSessionOptions()

        assertEquals(Dispatchers.Default, options.dispatcher)
        assertEquals(TimeSource.Monotonic, options.timeSource)
        assertSame(Random.Default, options.random)
        assertEquals(500, options.journalCapacity)
        assertEquals(DeployTiming(10.seconds, 3.seconds, 20, 250.milliseconds), options.deployTiming)
        assertEquals(25 to 200.milliseconds, options.subscribeAttempts to options.subscribeRetryDelay)
        assertEquals(3 to 1.seconds, options.snapshotAttempts to options.snapshotRetryDelay)
        assertEquals(DeployOptions(start = true, observe = true), DeployOptions())
        assertFailsWith<IllegalArgumentException> { RuntimeSessionOptions(journalCapacity = 0) }
        assertFailsWith<IllegalArgumentException> { RuntimeSessionOptions(subscribeAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RuntimeSessionOptions(snapshotAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RuntimeSessionOptions(snapshotRetryDelay = (-1).seconds) }
    }

    @Test
    fun `the factory prepares with the given planner`() = runTest {
        val rig = SessionRig(this, root)
        val opened = rig.open(SynthesizedModel.LAMP)
        val blocking = DeployProblem(Severity.ERROR, DeployProblemCodes.NO_MODEL, "no model", null)
        val planner = object : DeploymentPlanner {
            override fun plan(analysis: ProjectAnalysis, descriptor: Path, unsaved: Set<Path>): PlanResult = PlanResult.Blocked(listOf(blocking))
        }

        val preparation = RuntimeSessions(rig.options, planner = planner).prepare(opened.workspace, opened.descriptor)

        assertEquals(Preparation.Blocked(listOf(blocking)), preparation)
    }

    @Test
    fun `a launched session deploys with the given deployer, as its client id, and starts deploying at once`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val requests = CompletableDeferred<DeployRequest>()
        val failure = DeployFailure(
            StepProgress(DeployStepKind.CREATE, "Creating the environment", StepStatus.FAILED, "Unexpected error"),
            DeployProblem(Severity.ERROR, DeployProblemCodes.RUNTIME_ERROR, "Unexpected error", null),
            null,
        )
        val deployer = Deployer { request, _ ->
            requests.complete(request)
            DeploymentResult.Failed(failure, CleanupReport(emptyList(), null))
        }
        val options = DeployOptions(start = false, observe = false)

        val session = RuntimeSessions(rig.options, deployer = deployer).launch(deployment.prepared, rig.client, backgroundScope, options)

        assertEquals(SessionState.Deploying, session.state.value)
        assertEquals(ClientId.random(Random(SessionRig.SEED)), session.clientId)
        assertSame(deployment.prepared, session.prepared)
        assertSame(deployment.prepared.catalog, session.events.catalog)
        val request = requests.await()
        assertSame(deployment.prepared.plan, request.plan)
        assertSame(rig.client, request.client)
        assertEquals(session.clientId, request.clientId)
        assertEquals(options, request.options)
        assertEquals(SessionState.Failed(failure.problem, null), rig.awaitState(session) { it is SessionState.Failed })
        session.close()
    }

    @Test
    fun `the default deployer records what stays in the given ledger`() = runTest {
        val rig = SessionRig(this, root)
        val deployment = rig.prepared(SynthesizedModel.LAMP)
        val ledger = EnvironmentLedger()
        rig.fake.respond(RuntimeRoute.MODEL_SNAPSHOT, SessionRig.missingEnvironmentKey())
        rig.fake.respond(RuntimeRoute.SET_METAMODEL, FakeResponse(FakeResponse.SERVER_ERROR, "Unexpected error", FakeResponse.TEXT))

        val session = RuntimeSessions(rig.options, ledger).launch(deployment.prepared, rig.client, backgroundScope)

        val failed = assertIs<SessionState.Failed>(rig.awaitState(session) { it is SessionState.Failed })
        assertEquals(listOf(failed.leftBehind), ledger.entries.value)
        assertEquals(LedgerDisposition.LEFT_CONFIGURED, failed.leftBehind?.disposition)
        session.close()
    }
}
