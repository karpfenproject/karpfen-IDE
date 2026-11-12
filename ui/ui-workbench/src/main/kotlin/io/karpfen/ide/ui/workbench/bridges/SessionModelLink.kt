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
package io.karpfen.ide.ui.workbench.bridges

import io.karpfen.ide.bridges.api.Delivery
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.message.EventFrame
import io.karpfen.ide.runtime.protocol.message.EventPayload
import io.karpfen.ide.runtime.protocol.message.RuntimePush
import io.karpfen.ide.runtime.protocol.message.WireField
import io.karpfen.ide.runtime.protocol.message.WireValue
import io.karpfen.ide.runtime.session.RuntimeSession
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.events.InjectionOutcome
import io.karpfen.ide.runtime.session.events.InjectionRequest
import io.karpfen.ide.runtime.session.events.PayloadKind
import io.karpfen.ide.runtime.session.mirror.MirrorObject
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.observation.ObservedModel
import io.karpfen.ide.runtime.session.observation.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The [ModelLink] of the bridges that run inside the IDE. It shares the [session] of a run, so a bridge needs no
 * client of its own on the runtime.
 *
 * An event of a bridge goes through the injector of the session. The injector checks it like an event that is
 * injected by hand. An event it refuses, such as one whose payload does not fit the type of the event, is not sent,
 * and the reason shows in the status of the route. An event that no state machine listens to is sent all the same,
 * as it is when injected by hand.
 *
 * The pushes come from two places. The domain events are those the session observes, and they keep coming while
 * observation is off. The changes of objects and of active states are read from the observed model of the session.
 * Every change of that model gives one push per object and per state machine that differs from before, and the first
 * look at the model gives one push for each of them. So a route that follows a property or a state machine says the
 * current value once at the start. These changes only arrive while the run observes the model, because the session
 * does not hear of them otherwise.
 */
internal class SessionModelLink(private val session: RuntimeSession) : ModelLink {

    override val pushes: Flow<RuntimePush> = merge(
        session.domainEvents.map { RuntimePush.DomainEvent(it.domain, it.event, it.rawPayload) },
        changesOf(session.model),
    )

    override suspend fun send(event: EventFrame): Delivery {
        val request = when (val payload = event.payload) {
            EventPayload.None -> InjectionRequest(event.domain, event.name, PayloadKind.NONE, "")
            is EventPayload.Json -> InjectionRequest(event.domain, event.name, PayloadKind.JSON, payload.text)
            is EventPayload.Kmodel -> InjectionRequest(event.domain, event.name, PayloadKind.KMODEL, payload.text)
        }
        return when (val outcome = session.events.inject(request)) {
            is InjectionOutcome.Sent -> Delivery.Delivered
            is InjectionOutcome.Refused -> Delivery.Failed(
                outcome.validation.diagnostics.firstOrNull { it.severity == Severity.ERROR }?.message
                    ?: "The runtime session refused the event.",
            )
            is InjectionOutcome.Unavailable -> Delivery.Failed(reasonOf(outcome.availability))
        }
    }

    private companion object {

        /** Why an event could not be injected while the injector is in the state [availability]. */
        fun reasonOf(availability: InjectionAvailability): String = when (availability) {
            InjectionAvailability.NotYet -> "The environment is still being deployed."
            InjectionAvailability.Queued -> "The environment is not started."
            InjectionAvailability.Live -> "The connection to the runtime could not take the event."
            is InjectionAvailability.Unavailable -> availability.reason
        }

        /** The pushes that tell how each value of [models] differs from the one before, as the class describes. */
        fun changesOf(models: Flow<ObservedModel>): Flow<RuntimePush> = flow {
            var previous: ObservedModel? = null
            models.collect { next ->
                val before = previous
                val revision = revisionOf(next.sync)
                for ((id, value) in next.mirror.objects) {
                    if (before?.mirror?.objects?.get(id) != value) emit(RuntimePush.ObjectChanged(id, revision, wireOf(value)))
                }
                for ((key, machine) in next.states.machines) {
                    if (before?.states?.machines?.get(key)?.stack != machine.stack) {
                        emit(RuntimePush.StateChanged(machine.attachedTo, machine.stack, revision))
                    }
                }
                previous = next
            }
        }

        /** The revision that [sync] is at, or 0 if nothing was read from the runtime yet. */
        fun revisionOf(sync: SyncStatus): Long = when (sync) {
            is SyncStatus.Live -> sync.revision
            is SyncStatus.Pulled -> sync.revision
            is SyncStatus.Final -> sync.revision
            SyncStatus.Predicted, is SyncStatus.Synchronizing -> 0
        }

        /** The properties of [value] as the JSON object a push of the runtime would carry. */
        fun wireOf(value: MirrorObject): WireValue.WireObject = WireValue.WireObject(
            value.properties.map { property ->
                WireField(
                    property.key,
                    when (val held = property.value) {
                        is PropertyValue.Single -> wireOf(held.value)
                        is PropertyValue.Many -> WireValue.WireArray(held.values.map(::wireOf))
                    },
                )
            },
        )

        fun wireOf(value: ScalarValue): WireValue = when (value) {
            is ScalarValue.Number -> WireValue.WireNumber(value.value)
            is ScalarValue.Text -> WireValue.WireString(value.value)
            is ScalarValue.Flag -> WireValue.WireBoolean(value.value)
        }
    }
}
