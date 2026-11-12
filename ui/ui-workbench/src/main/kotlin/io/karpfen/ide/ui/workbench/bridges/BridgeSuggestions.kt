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

import io.karpfen.ide.core.workspace.analysis.ContextAnalysis
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.runtime.session.events.EventCatalogBuilder
import io.karpfen.ide.runtime.session.events.EventSources
import io.karpfen.ide.runtime.session.events.InjectableEvent
import io.karpfen.ide.runtime.session.events.ListeningMachine
import io.karpfen.ide.runtime.session.mirror.MirrorSeeder
import io.karpfen.ide.runtime.session.schema.TypeSchema
import org.slf4j.LoggerFactory

/**
 * What the bridge form offers to pick, so the user does not have to type the names of the project. Each entry is
 * written as the form writes it.
 *
 * @property events the events of the project as domain/name, for the event of an inbound route.
 * @property reportedEvents the events an outbound route can follow, as domain/name. Those a state machine raises come
 *   first, then the others.
 * @property properties the properties of the keyed objects of the models, as object/property.
 * @property machines the keys of the objects that state machines are attached to.
 */
public data class BridgeSuggestions(
    public val events: List<String> = emptyList(),
    public val reportedEvents: List<String> = emptyList(),
    public val properties: List<String> = emptyList(),
    public val machines: List<String> = emptyList(),
) {
    /** What an outbound route that follows [follows] can pick. */
    public fun followed(follows: Follows): List<String> = when (follows) {
        Follows.PROPERTY -> properties
        Follows.STATE -> machines
        Follows.EVENT -> reportedEvents
    }

    public companion object {
        private val logger = LoggerFactory.getLogger(BridgeSuggestions::class.java)

        /**
         * The suggestions of the environments that [analysis] knows, which are the contexts with a descriptor. The
         * events come from the event catalog of each environment, the same one the Events pane of a run shows. The
         * properties come from the model as the runtime starts it. A context whose files are too broken to read gives
         * what can still be read of it, and nothing in the worst case, because suggestions are only a help.
         */
        public fun of(analysis: ProjectAnalysis): BridgeSuggestions {
            val symbols = ContextSymbols(analysis)
            val contexts = analysis.contexts.filter { it.context.descriptor != null }
            val events = contexts.flatMap { context -> attempt("events", context) { eventsOf(analysis, symbols, context) } }
            return BridgeSuggestions(
                events = events.map { it.written }.distinct(),
                reportedEvents = (events.filter { it.raisers.isNotEmpty() } + events).map { it.written }.distinct(),
                properties = contexts.flatMap { context -> attempt("properties", context) { propertiesOf(context) } }.distinct(),
                machines = contexts.flatMap { context -> context.context.stateMachines.mapNotNull { it.attachedTo } }
                    .filter { it.isNotBlank() }
                    .distinct(),
            )
        }

        private val InjectableEvent.written: String get() = "$domain/$name"

        /** The events of the catalog of [context], or none if its metamodel was not read. */
        private fun eventsOf(analysis: ProjectAnalysis, symbols: ContextSymbols, context: ContextAnalysis): List<InjectableEvent> {
            val metamodel = context.context.metamodel?.let(analysis::file)?.snapshot ?: return emptyList()
            val machines = context.context.stateMachines.mapNotNull { binding ->
                val attachedTo = binding.attachedTo ?: return@mapNotNull null
                context.stateMachines[binding.file]?.let { ListeningMachine(attachedTo, binding.file, it) }
            }
            val eventsFile = context.context.events
            return EventCatalogBuilder.build(
                domains = symbols.eventDomainsOf(machines.map { it.file }),
                machines = machines,
                eventsMetamodel = context.events,
                eventTypeNames = eventsFile?.let(symbols::typeNamesOf).orEmpty(),
                sources = EventSources(metamodel, eventsFile?.let(analysis::file)?.snapshot),
            ).events
        }

        /** The properties of the keyed objects of the models of [context], or none if they were not read. */
        private fun propertiesOf(context: ContextAnalysis): List<String> {
            val schema = context.metamodel?.let(TypeSchema::of) ?: return emptyList()
            return context.models.values.flatMap { model ->
                MirrorSeeder.seed(model, schema).objects.values.flatMap { value -> value.properties.map { "${value.id}/${it.key}" } }
            }
        }

        /** What [read] returns, or nothing if the half-read files of [context] make it fail. */
        private fun <T> attempt(what: String, context: ContextAnalysis, read: () -> List<T>): List<T> = try {
            read()
        } catch (failure: RuntimeException) {
            logger.info("No {} to suggest from {}: {}", what, context.context.name, failure.toString())
            emptyList()
        }
    }
}
