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
package io.karpfen.ide.runtime.session.observation

import io.karpfen.ide.runtime.protocol.api.RuntimeFailure
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.RejectionKind
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.connection.ConnectionEnd
import io.karpfen.ide.runtime.protocol.connection.ConnectionEvent
import io.karpfen.ide.runtime.protocol.lifecycle.CallOutcome
import io.karpfen.ide.runtime.protocol.message.ProtocolViolation
import io.karpfen.ide.runtime.protocol.message.PublishedEvent
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.RuntimePhase
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.ViolationKind
import io.karpfen.ide.runtime.protocol.transport.LinkClosure
import io.karpfen.ide.runtime.session.examples.ReadModel
import io.karpfen.ide.runtime.session.examples.WireTexts
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.states.ActiveStatesReducer
import io.karpfen.ide.runtime.session.states.MachineLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the model observer with a port that answers as each test programs it, on virtual time: switching on with
 * retries while the runtime has no session for the client, pushes that wait in the router's inbox while a snapshot is
 * read, a gap, a reconnect, failed snapshots and the out-of-sync report, switching off with late pushes, pulls (also
 * while the link is down) and the final snapshot after the stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelObserverTest {

    private val lamp = ReadModel.synthesized(SynthesizedModel.LAMP)

    private val notices = ArrayList<ObservationNotice>()

    @Test
    fun `switching on retries the subscription while the runtime has no session for the client, then reads the snapshot`() = runTest {
        val port = FakePort(this)
        port.subscriptions += listOf(NO_SESSION, NO_SESSION)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)

        val outcome = observer.switchOn()

        assertEquals(CallOutcome.Done(snapshot(10)), outcome)
        assertEquals(listOf("subscribe at 0", "subscribe at 200", "subscribe at 400", "snapshot at 400"), port.calls)
        assertEquals(ObservationMode.ON, observer.model.value.mode)
        assertEquals(SyncStatus.Live(10), observer.model.value.sync)
        assertEquals(listOf<ObservationNotice>(ObservationNotice.Synchronized(SyncReason.SWITCHED_ON, 10)), notices)
    }

    @Test
    fun `switching on gives up after the subscription attempts and leaves observation off`() = runTest {
        val port = FakePort(this)
        port.subscriptions += listOf(NO_SESSION, NO_SESSION, NO_SESSION)
        val observer = observer(port)

        val outcome = observer.switchOn()

        assertEquals<CallOutcome<*>>(NO_SESSION, outcome)
        assertEquals(listOf("subscribe at 0", "subscribe at 200", "subscribe at 400"), port.calls)
        assertEquals(ObservationMode.OFF, observer.model.value.mode)
        assertEquals(SyncStatus.Predicted, observer.model.value.sync)
    }

    @Test
    fun `another refusal of the subscription is answered at once`() = runTest {
        val port = FakePort(this)
        val unknown = CallOutcome.Failed(
            RuntimeFailure.Rejected(RuntimeRoute.SUBSCRIBE_MODEL, 400, RejectionKind.UNKNOWN_ENVIRONMENT, "Environment with key env-1 does not exist", emptyMap()),
        )
        port.subscriptions += unknown

        assertEquals(unknown, observer(port).switchOn())
        assertEquals(listOf("subscribe at 0"), port.calls)
    }

    @Test
    fun `pushes that arrive while the first snapshot is read wait, then those it covers are dropped and the rest applied in order`() = runTest {
        val port = FakePort(this)
        val gate = CompletableDeferred<CallOutcome<RuntimeModelSnapshot>>()
        port.snapshots += { gate.await() }
        val observer = observer(port)
        val updates = collectUpdates(observer)
        val router = TestRouter(backgroundScope, observer)

        val switched = router.command { switchOn() }
        runCurrent()
        router.deliver(ConnectionEvent.Received(bulb(9, watts = 50)))
        router.deliver(ConnectionEvent.Received(bulb(10, watts = 55)))
        router.deliver(ConnectionEvent.Received(RuntimePush.StateChanged("lamp", listOf("on"), 11)))
        router.deliver(ConnectionEvent.Received(bulb(12, watts = 100)))
        runCurrent()
        assertEquals(SyncStatus.Synchronizing(SyncReason.SWITCHED_ON), observer.model.value.sync)

        gate.complete(CallOutcome.Done(snapshot(10)))
        runCurrent()

        assertEquals(CallOutcome.Done(snapshot(10)), switched.await())
        assertEquals(SyncStatus.Live(12), observer.model.value.sync)
        assertEquals(listOf("on"), observer.model.value.states.machines.getValue("lamp").stack)
        assertEquals(PropertyValue.Single(ScalarValue.Number(100.0)), watts(observer))
        assertEquals(
            listOf(
                ModelUpdate(10, emptySet(), emptySet(), emptySet(), emptySet(), UpdateCause.SNAPSHOT),
                ModelUpdate(11, emptySet(), emptySet(), emptySet(), setOf("lamp"), UpdateCause.PUSH),
                ModelUpdate(12, setOf("bulb"), emptySet(), emptySet(), emptySet(), UpdateCause.PUSH),
            ),
            updates,
        )
    }

    @Test
    fun `a gap re-reads the snapshot and goes on from its revision`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        port.snapshots += answer(snapshot(14))
        val observer = observer(port)
        observer.switchOn()

        observer.handle(ConnectionEvent.Received(bulb(13, watts = 60)))
        observer.handle(ConnectionEvent.Received(bulb(14, watts = 70)))
        observer.handle(ConnectionEvent.Received(bulb(15, watts = 80)))

        assertEquals(SyncStatus.Live(15), observer.model.value.sync)
        assertEquals(PropertyValue.Single(ScalarValue.Number(80.0)), watts(observer))
        assertEquals(listOf("subscribe at 0", "snapshot at 0", "snapshot at 0"), port.calls)
        assertEquals(
            listOf(
                ObservationNotice.Synchronized(SyncReason.SWITCHED_ON, 10),
                ObservationNotice.Anomaly("A push with revision 13 arrived after revision 10: revisions 11 to 12 did not arrive."),
                ObservationNotice.Synchronized(SyncReason.GAP, 14),
            ),
            notices,
        )
    }

    @Test
    fun `after a reconnect the observation subscribes again, with retries, and re-reads the snapshot`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()

        observer.handle(ConnectionEvent.Interrupted(LinkClosure(null, null, byClient = false)))
        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), observer.model.value.sync)
        port.subscriptions += NO_SESSION
        port.snapshots += answer(snapshot(15))
        observer.handle(ConnectionEvent.Opened(2))

        assertEquals(SyncStatus.Live(15), observer.model.value.sync)
        assertEquals(listOf("subscribe at 0", "snapshot at 0", "subscribe at 0", "subscribe at 200", "snapshot at 200"), port.calls)
        assertEquals(ObservationNotice.Synchronized(SyncReason.RECONNECTED, 15), notices.last())
    }

    @Test
    fun `a reconnect whose subscription fails is reported out of sync and reads no snapshot`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()
        port.subscriptions += listOf(NO_SESSION, NO_SESSION, NO_SESSION)

        observer.handle(ConnectionEvent.Opened(2))

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), observer.model.value.sync)
        assertEquals(listOf("subscribe at 0", "snapshot at 0", "subscribe at 0", "subscribe at 200", "subscribe at 400"), port.calls)
        assertTrue(notices.last() is ObservationNotice.OutOfSync)
    }

    @Test
    fun `failed snapshots are retried, then observation is out of sync until a pull succeeds`() = runTest {
        val port = FakePort(this)
        port.snapshots += listOf(answer(NO_ANSWER), answer(NO_ANSWER), answer(NO_ANSWER))
        val observer = observer(port)

        val outcome = observer.switchOn()

        assertEquals(NO_ANSWER, outcome)
        assertEquals(listOf("subscribe at 0", "snapshot at 0", "snapshot at 1000", "snapshot at 2000"), port.calls)
        assertEquals(SyncStatus.Synchronizing(SyncReason.SNAPSHOT_FAILED), observer.model.value.sync)
        assertSame(lamp.seed, observer.model.value.mirror)
        assertEquals(1, notices.count { it is ObservationNotice.OutOfSync })

        port.snapshots += answer(snapshot(20))
        observer.pull()

        assertEquals(SyncStatus.Live(20), observer.model.value.sync)
    }

    @Test
    fun `a snapshot that succeeds on a retry needs no report`() = runTest {
        val port = FakePort(this)
        port.snapshots += listOf(answer(NO_ANSWER), answer(snapshot(4)))
        val observer = observer(port)

        observer.switchOn()

        assertEquals(SyncStatus.Live(4), observer.model.value.sync)
        assertEquals(listOf("subscribe at 0", "snapshot at 0", "snapshot at 1000"), port.calls)
        assertEquals(listOf<ObservationNotice>(ObservationNotice.Synchronized(SyncReason.SWITCHED_ON, 4)), notices)
    }

    @Test
    fun `switched off, late pushes are ignored and a pull shows the model as pulled`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()

        assertEquals(CallOutcome.Done(Unit), observer.switchOff())
        observer.handle(ConnectionEvent.Received(bulb(11, watts = 60)))

        assertEquals(SyncStatus.Pulled(10), observer.model.value.sync)
        assertEquals(PropertyValue.Single(ScalarValue.Number(40.0)), watts(observer))

        port.snapshots += answer(snapshot(20))
        assertEquals(CallOutcome.Done(snapshot(20)), observer.pull())
        assertEquals(SyncStatus.Pulled(20), observer.model.value.sync)
        assertEquals(listOf("subscribe at 0", "snapshot at 0", "unsubscribe at 0", "snapshot at 0"), port.calls)
    }

    @Test
    fun `a failed unsubscription leaves observation on`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()
        val failed = CallOutcome.Failed(RuntimeFailure.Unreachable(RuntimeRoute.UNSUBSCRIBE_MODEL, "connection refused"))
        port.unsubscriptions += failed

        assertEquals(failed, observer.switchOff())
        assertEquals(SyncStatus.Live(10), observer.model.value.sync)
    }

    @Test
    fun `a failed pull while live keeps the model live, so the next push is applied`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        port.snapshots += answer(NO_ANSWER)
        val observer = observer(port)
        observer.switchOn()

        assertEquals(NO_ANSWER, observer.pull())
        observer.handle(ConnectionEvent.Received(bulb(11, watts = 60)))

        assertEquals(SyncStatus.Live(11), observer.model.value.sync)
        assertEquals(PropertyValue.Single(ScalarValue.Number(60.0)), watts(observer))
        assertEquals(0, notices.count { it is ObservationNotice.OutOfSync })
    }

    @Test
    fun `a failed pull while off changes nothing and is not reported out of sync`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(NO_ANSWER)
        val observer = observer(port)
        val before = observer.model.value

        assertEquals(NO_ANSWER, observer.pull())
        assertSame(before, observer.model.value)
        assertEquals(listOf("snapshot at 0"), port.calls)
        assertEquals(emptyList(), notices)
    }

    @Test
    fun `after the stop the final snapshot settles the model, and later pushes are ignored`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()
        port.snapshots += answer(snapshot(30, RuntimePhase.STOPPED))

        val outcome = observer.environmentStopped()
        observer.handle(ConnectionEvent.Received(bulb(31, watts = 60)))

        assertEquals(CallOutcome.Done(snapshot(30, RuntimePhase.STOPPED)), outcome)
        assertEquals(ObservationMode.OFF, observer.model.value.mode)
        assertEquals(SyncStatus.Final(30), observer.model.value.sync)
        assertEquals(RuntimePhase.STOPPED, observer.model.value.phase)
        assertEquals(PropertyValue.Single(ScalarValue.Number(40.0)), watts(observer))
        assertEquals(ObservationNotice.Synchronized(null, 30), notices.last())
    }

    @Test
    fun `a connection that ends while on leaves observation synchronizing, and a pull reads the model without making it live`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()

        observer.handle(ConnectionEvent.Ended(ConnectionEnd.GaveUp(20, "connection refused")))

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), observer.model.value.sync)
        port.snapshots += answer(snapshot(17))
        assertEquals(CallOutcome.Done(snapshot(17)), observer.pull())
        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), observer.model.value.sync)
        assertEquals(ObservationMode.ON, observer.model.value.mode)
        assertEquals(17L, observer.model.value.mirror.objects.getValue("bulb").revision)
        assertEquals(ObservationNotice.Synchronized(null, 17), notices.last(), "a plain read, no re-synchronization")
    }

    @Test
    fun `a pull while the link is interrupted leaves the model synchronizing until the read after the reconnect`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()
        observer.handle(ConnectionEvent.Interrupted(LinkClosure(null, null, byClient = false)))
        port.snapshots += answer(snapshot(12))

        observer.pull()

        assertEquals(SyncStatus.Synchronizing(SyncReason.RECONNECTED), observer.model.value.sync)
        port.snapshots += answer(snapshot(13))
        observer.handle(ConnectionEvent.Opened(2))
        assertEquals(SyncStatus.Live(13), observer.model.value.sync)
        assertEquals(ObservationNotice.Synchronized(SyncReason.RECONNECTED, 13), notices.last())
    }

    @Test
    fun `violations, domain events and unrecognized pushes leave the model as it is`() = runTest {
        val port = FakePort(this)
        port.snapshots += answer(snapshot(10))
        val observer = observer(port)
        observer.switchOn()
        val before = observer.model.value
        val noticesBefore = notices.size

        observer.handle(ConnectionEvent.Violated(ProtocolViolation(ViolationKind.BINARY_FRAME, "a binary frame of 4 bytes", "")))
        observer.handle(
            ConnectionEvent.Received(
                RuntimePush.DomainEvent("switch", PublishedEvent("press", "", "none", "EXTERNAL_MESSAGE", 1_761_560_130_123, 3_000), "{}"),
            ),
        )
        observer.handle(ConnectionEvent.Received(RuntimePush.Unrecognized("somethingNew", "{}")))
        observer.handle(ConnectionEvent.Opened(1))

        assertSame(before, observer.model.value)
        assertEquals(noticesBefore, notices.size)
    }

    /** The observer of the lamp model over [port], reporting to [notices]. */
    private fun observer(port: ObservationPort): ModelObserver =
        ModelObserver(
            ObservationReducer(lamp.reducer(), ActiveStatesReducer(listOf(LAMP, SWITCH))),
            port,
            lamp.seed,
            SETTINGS,
            ObservationListener { notices += it },
        )

    /** Collects the updates of [observer] from now on, as they are emitted. */
    private fun TestScope.collectUpdates(observer: ModelObserver): List<ModelUpdate> {
        val updates = ArrayList<ModelUpdate>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.updates.collect { updates += it } }
        return updates
    }

    /** The watts of the lamp's bulb as [observer] shows them. */
    private fun watts(observer: ModelObserver): PropertyValue? =
        observer.model.value.mirror.objects.getValue("bulb").property("watts")?.value

    /** A push of the lamp's bulb with [watts] at [revision]. */
    private fun bulb(revision: Long, watts: Int): RuntimePush.ObjectChanged =
        WireTexts.changed("bulb", revision, "{\"__id__\": \"bulb\", \"__type__\": \"Bulb\", \"watts\": $watts, \"lit\": false}")

    /** A snapshot of the lamp model as deployed, at [revision] and [phase]. */
    private fun snapshot(revision: Long, phase: RuntimePhase = RuntimePhase.RUNNING): RuntimeModelSnapshot =
        RuntimeModelSnapshot(EnvironmentKey("env-1"), phase, revision, emptyMap(), lamp.wireRoots())

    /** An answer of the port that is [outcome] at once. */
    private fun answer(outcome: CallOutcome<RuntimeModelSnapshot>): suspend () -> CallOutcome<RuntimeModelSnapshot> = { outcome }

    /** An answer of the port that is the read [snapshot] at once. */
    private fun answer(snapshot: RuntimeModelSnapshot): suspend () -> CallOutcome<RuntimeModelSnapshot> = { CallOutcome.Done(snapshot) }

    /**
     * A port whose answers each test programs, in order; without a programmed answer a subscription or unsubscription
     * succeeds, and a snapshot fails the test. It records every call with the virtual time it was made at.
     *
     * @param scope the test, whose virtual time the calls record.
     */
    private class FakePort(private val scope: TestScope) : ObservationPort {
        /** The calls in order, each with its virtual time in milliseconds. */
        val calls = ArrayList<String>()

        /** The answers to the next subscriptions. */
        val subscriptions = ArrayDeque<CallOutcome<Unit>>()

        /** The answers to the next unsubscriptions. */
        val unsubscriptions = ArrayDeque<CallOutcome<Unit>>()

        /** The answers to the next snapshot reads, each suspending as long as the test wants. */
        val snapshots = ArrayDeque<suspend () -> CallOutcome<RuntimeModelSnapshot>>()

        /** Records the subscription and answers the next programmed outcome or success. */
        override suspend fun subscribe(): CallOutcome<Unit> {
            record("subscribe")
            return subscriptions.removeFirstOrNull() ?: CallOutcome.Done(Unit)
        }

        /** Records the unsubscription and answers the next programmed outcome or success. */
        override suspend fun unsubscribe(): CallOutcome<Unit> {
            record("unsubscribe")
            return unsubscriptions.removeFirstOrNull() ?: CallOutcome.Done(Unit)
        }

        /** Records the read and answers the next programmed answer. */
        override suspend fun snapshot(): CallOutcome<RuntimeModelSnapshot> {
            record("snapshot")
            return checkNotNull(snapshots.removeFirstOrNull()) { "no snapshot answer programmed" }()
        }

        /** Records the call [name] at the current virtual time. */
        private fun record(name: String) {
            calls += "$name at ${scope.testScheduler.currentTime}"
        }
    }

    /**
     * The session's router as far as the observer needs it: one coroutine takes connection events and commands from an
     * unbounded inbox, one at a time, so events that arrive while a command waits for the runtime queue up.
     *
     * @param scope where the router's coroutine runs.
     * @param observer the observer it drives.
     */
    private class TestRouter(scope: CoroutineScope, private val observer: ModelObserver) {
        /** The inbox of work, taken in order. */
        private val inbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        init {
            scope.launch { for (work in inbox) work() }
        }

        /** Puts [event] into the inbox. */
        fun deliver(event: ConnectionEvent) {
            inbox.trySend { observer.handle(event) }
        }

        /** Puts the command [block] into the inbox and answers its outcome when it ran. */
        fun <T> command(block: suspend ModelObserver.() -> T): Deferred<T> {
            val outcome = CompletableDeferred<T>()
            inbox.trySend { outcome.complete(observer.block()) }
            return outcome
        }
    }

    private companion object {
        /** The retries of the tests. */
        val SETTINGS = ObservationSettings(
            subscribeAttempts = 3,
            subscribeRetryDelay = 200.milliseconds,
            snapshotAttempts = 3,
            snapshotRetryDelay = 1.seconds,
        )

        /** The runtime's refusal of a subscription before it processed the handshake. */
        val NO_SESSION: CallOutcome<Unit> = CallOutcome.Failed(
            RuntimeFailure.Rejected(
                RuntimeRoute.SUBSCRIBE_MODEL,
                409,
                RejectionKind.NO_SESSION,
                "Client ide-4f2c has no open WebSocket session for environment env-1",
                mapOf("client" to "ide-4f2c", "env" to "env-1"),
            ),
        )

        /** A snapshot read the runtime did not answer in time. */
        val NO_ANSWER: CallOutcome<RuntimeModelSnapshot> =
            CallOutcome.Failed(RuntimeFailure.NoAnswer(RuntimeRoute.MODEL_SNAPSHOT, "timed out", timedOut = true))

        /** The lamp's machine. */
        val LAMP = MachineLayout("lamp", Path.of("lamp.kstates"), listOf("off", "on", "broken"), listOf("off"))

        /** The wall switch's machine. */
        val SWITCH = MachineLayout("wallSwitch", Path.of("switch.kstates"), listOf("down", "up", "back down"), listOf("down"))
    }
}
