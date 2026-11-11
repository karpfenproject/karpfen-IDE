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
package io.karpfen.ide.runtime.session.events

import io.karpfen.dsl.states.JoinTransition
import io.karpfen.dsl.states.SplitTransition
import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.states.Transition
import io.karpfen.dsl.states.TransitionLike
import io.karpfen.dsl.states.actions.ActionBlock
import io.karpfen.dsl.states.actions.ActionItem
import io.karpfen.dsl.states.actions.ActionOperationType
import io.karpfen.dsl.states.actions.ActionRule
import io.karpfen.dsl.states.actions.EvalActionRightSide
import io.karpfen.dsl.states.actions.InScopeBlock
import io.karpfen.dsl.states.actions.MacroActionRightSide
import io.karpfen.dsl.states.actions.ValueActionRightSide
import io.karpfen.dsl.states.actions.WithBlock
import io.karpfen.dsl.states.conditions.CompositeCondition
import io.karpfen.dsl.states.conditions.Condition
import io.karpfen.dsl.states.conditions.EvalCondition
import io.karpfen.dsl.states.conditions.EventCondition
import io.karpfen.dsl.states.conditions.ValueCondition
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ContextSymbols
import io.karpfen.ide.runtime.session.examples.AnalyzedContext
import io.karpfen.ide.runtime.session.examples.AnalyzedContexts
import io.karpfen.ide.runtime.session.examples.DslTexts
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the event catalog. It checks the domains of the examples and synthesized models with their origins and the
 * listened events, which are the first event condition of every transition. It also checks the raised events in every
 * block and that payload types are resolved as the runtime resolves them.
 */
class EventCatalogBuilderTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the single robot's public domain lists start as listened and the two raised events, then the other declared events`() = runTest {
        val robot = AnalyzedContexts.runtimeExamples(this, root).first()
        val file = robot.machines.single().file
        val catalog = catalogOf(robot)

        assertEquals(listOf("public"), catalog.domains.map { it.name })
        assertEquals(DomainOrigin.DECLARED, catalog.domain("public")?.origin)
        assertEquals(
            listOf("start", "oh nooo there is an obstacle", "oh nooo there is a wall", "setSpeed", "teleport"),
            catalog.events.map { it.name },
        )
        assertEquals(
            InjectableEvent(
                domain = "public",
                name = "start",
                declared = true,
                payloadType = "start",
                payloadTypeOrigin = PayloadTypeOrigin.EVENT_TYPE,
                documentation = "Signal to start operating. No payload.",
                listeners = listOf(EventRef("turtle", file, listOf("ready"))),
                raisers = emptyList(),
            ),
            catalog.events.first(),
        )
        assertEquals(
            InjectableEvent(
                domain = "public",
                name = "oh nooo there is an obstacle",
                declared = false,
                payloadType = null,
                payloadTypeOrigin = null,
                documentation = null,
                listeners = emptyList(),
                raisers = listOf(EventRef("turtle", file, listOf("react to obstacle"))),
            ),
            catalog.events[1],
        )
        assertEquals(listOf(EventRef("turtle", file, listOf("react to wall"))), catalog.events[2].raisers)
        assertEquals(listOf(true, true), catalog.events.drop(3).map { it.declared })
        assertEquals(listOf("setSpeed", "teleport"), catalog.events.drop(3).map { it.payloadType })
        assertTrue(catalog.events.drop(3).all { it.listeners.isEmpty() && it.raisers.isEmpty() })
        assertEquals(listOf("start", "setSpeed", "teleport"), catalog.schema?.names?.take(3))
        assertEquals("Vector", catalog.schema?.names?.get(3))
    }

    @Test
    fun `the lamp's two domains are declared, and one event reaches both machines`() = runTest {
        val lamp = AnalyzedContexts.allSynthesized(this, root).single { it.name == "lamp" }
        val files = lamp.machines.associate { it.attachedTo to it.file }
        val catalog = catalogOf(lamp)

        assertEquals(listOf("service", "switch"), catalog.domains.map { it.name })
        assertEquals(listOf(DomainOrigin.DECLARED, DomainOrigin.DECLARED), catalog.domains.map { it.origin })
        assertEquals(listOf("smash"), catalog.domain("service")?.events?.map { it.name })
        assertEquals(listOf("press", "dim"), catalog.domain("switch")?.events?.map { it.name })
        assertEquals(
            listOf(
                EventRef("lamp", files.getValue("lamp"), listOf("off", "on")),
                EventRef("wallSwitch", files.getValue("wallSwitch"), listOf("down", "up", "back down")),
            ),
            catalog.event("switch", "press")?.listeners,
        )
        assertEquals(listOf(EventRef("lamp", files.getValue("lamp"), listOf("on"))), catalog.event("switch", "dim")?.listeners)
        assertEquals("Asks the lamp for full power when bright is true", catalog.event("switch", "dim")?.documentation)
    }

    @Test
    fun `the metronome's tick domain, whose raised event the events file does not declare, is only used`() = runTest {
        val metronome = AnalyzedContexts.allSynthesized(this, root).single { it.name == "metronome" }
        val catalog = catalogOf(metronome)

        assertEquals(listOf("beat" to DomainOrigin.DECLARED, "tick" to DomainOrigin.USED_ONLY), catalog.domains.map { it.name to it.origin })
        assertEquals(listOf("go"), catalog.domain("beat")?.events?.map { it.name })
        val burst = catalog.event("tick", "burst done")
        assertEquals(false, burst?.declared)
        assertNull(burst?.payloadType)
        assertEquals(listOf("lane b done"), burst?.raisers?.single()?.states)
    }

    @Test
    fun `the catalog's domains are the plan's, and every event the machines name belongs to one of them`() = runTest {
        val contexts = AnalyzedContexts.runtimeExamples(this, root.resolve("examples")) +
            AnalyzedContexts.allSynthesized(this, root.resolve("synthesized"))

        contexts.forEach { context ->
            val catalog = catalogOf(context)
            assertEquals(context.domains, catalog.domains.map { it.name }, "the domains of $context")
            val named = context.machines.flatMap { machine -> eventsNamedBy(machine.machine) }.toSet()
            val listed = catalog.events.map { it.domain to it.name }.toSet()
            assertTrue(listed.containsAll(named), "every event $context names is listed: ${named - listed}")
            assertTrue(named.all { it.first in context.domains }, "every domain $context uses is a plan domain")
        }
    }

    @Test
    fun `only the first event condition of a transition counts, after flattening its clauses in order`() {
        val machine = machine(
            transitions = listOf(
                Transition("a", "b", true, composite(composite(ValueCondition("ready"), EventCondition("ops", "first")), EventCondition("ops", "second"))),
                Transition("b", "a", true, composite(EvalCondition("return True"), ValueCondition("ready"))),
                SplitTransition("b", listOf("c", "d"), true, EventCondition("ops", "split")),
                JoinTransition(listOf("c", "d"), "a", true, EventCondition("ops", "join")),
            ),
        )

        val catalog = build(listOf("ops"), machine)

        assertEquals(listOf("first", "split", "join"), catalog.events.map { it.name })
        assertEquals(listOf("a"), catalog.event("ops", "first")?.listeners?.single()?.states)
        assertEquals(listOf("b"), catalog.event("ops", "split")?.listeners?.single()?.states)
        assertEquals(listOf("c", "d"), catalog.event("ops", "join")?.listeners?.single()?.states)
    }

    @Test
    fun `events raised in entry and do blocks, inner states, scope and macro blocks are found, macro and eval names are not`() {
        val inner = state(
            "inner",
            entry = listOf(InScopeBlock(listOf("event->x"), ActionBlock(mutableListOf(raise("ops", "in scope"))))),
            doing = listOf(WithBlock(MacroActionRightSide("m", emptyList()), "v", ActionBlock(mutableListOf(raise("ops", "in with"))))),
        )
        val outer = state(
            "outer",
            entry = listOf(
                raise("ops", "on entry"),
                ActionRule(ActionOperationType.EVENT, "ops", MacroActionRightSide("name_of", emptyList())),
                ActionRule(ActionOperationType.EVENT, "ops", EvalActionRightSide("return 'x'")),
                ActionRule(ActionOperationType.SET, "ops", ValueActionRightSide("not an event")),
            ),
            doing = listOf(raise("ops", "on do"), raise("other", "elsewhere")),
            inner = listOf(inner),
        )

        val catalog = build(listOf("ops"), machine(states = listOf(outer)))

        assertEquals(listOf("on entry", "on do", "in scope", "in with"), catalog.events.map { it.name })
        assertEquals(listOf("inner"), catalog.event("ops", "in with")?.raisers?.single()?.states)
        assertEquals(listOf("outer"), catalog.event("ops", "on entry")?.raisers?.single()?.states)
        assertNull(catalog.domain("other"))
    }

    @Test
    fun `a payload type is the events file's type of the event's name, else a domain type of that name, else none`() {
        val domain = DslTexts.metamodel("type \"Vector\" \"A point\" {\n    prop(\"x\", \"number\")\n}\n")
        val events = DslTexts.metamodel("type \"go\" \"Go\" {\n}\n", domain.types)
        val machine = machine(
            transitions = listOf(
                Transition("a", "b", true, EventCondition("ops", "go")),
                Transition("b", "c", true, EventCondition("ops", "Vector")),
                Transition("c", "a", true, EventCondition("ops", "nothing")),
            ),
        )

        val catalog = EventCatalogBuilder.build(listOf("ops"), listOf(ListeningMachine("r", FILE, machine)), events, listOf("go"), SOURCES)

        assertEquals(
            listOf(
                Triple("go", "go", PayloadTypeOrigin.EVENT_TYPE),
                Triple("Vector", "Vector", PayloadTypeOrigin.DOMAIN_TYPE),
                Triple("nothing", null, null),
            ),
            catalog.events.map { Triple(it.name, it.payloadType, it.payloadTypeOrigin) },
        )
        assertEquals("A point", catalog.event("ops", "Vector")?.documentation)
        assertEquals(listOf(true, false, false), catalog.events.map { it.declared })
        assertEquals(DomainOrigin.DECLARED, catalog.domain("ops")?.origin)
    }

    @Test
    fun `without event definitions no payload is ever parsed and every domain is only used`() {
        val machine = machine(transitions = listOf(Transition("a", "b", true, EventCondition("ops", "go"))))

        val catalog = EventCatalogBuilder.build(listOf("ops", "quiet"), listOf(ListeningMachine("r", FILE, machine)), null, emptyList(), SOURCES)

        assertNull(catalog.schema)
        assertNull(catalog.event("ops", "go")?.payloadType)
        assertEquals(listOf(DomainOrigin.USED_ONLY, DomainOrigin.USED_ONLY), catalog.domains.map { it.origin })
        assertEquals(emptyList(), catalog.domain("quiet")?.events)
        assertNull(catalog.event("ops", "missing"))
    }

    /** The catalog of [context], built as the preparation of its deployment builds it. */
    private fun catalogOf(context: AnalyzedContext): EventCatalog {
        val events = context.context.context.events
        val sources = EventSources(
            metamodel = checkNotNull(context.context.files[context.context.context.metamodel]).snapshot,
            events = events?.let { checkNotNull(context.context.files[it]).snapshot },
        )
        val typeNames = events?.let { ContextSymbols(context.analysis).typeNamesOf(it) }.orEmpty()
        val machines = context.machines.map { ListeningMachine(it.attachedTo, it.file, it.machine) }
        return EventCatalogBuilder.build(context.domains, machines, context.context.events, typeNames, sources)
    }

    /** The domain and name of every event [machine] names with a literal name, in conditions and actions. */
    private fun eventsNamedBy(machine: StateMachine): List<Pair<String, String>> =
        machine.transitions.mapNotNull { firstEvent(it.condition) }.map { it.eventDomain to it.eventValue } +
            machine.states.flatMap(::raisedBy)

    /** The domain and name of every event [state] and its inner states raise with a literal name. */
    private fun raisedBy(state: State): List<Pair<String, String>> =
        raisedIn(state.onEntry.items) + raisedIn(state.onDo.items) + state.innerStates.flatMap(::raisedBy)

    /** The domain and name of every event the actions [items] raise with a literal name, nested blocks included. */
    private fun raisedIn(items: List<ActionItem>): List<Pair<String, String>> =
        items.flatMap { item ->
            when (item) {
                is ActionRule -> listOfNotNull(
                    (item.rightSide as? ValueActionRightSide)?.takeIf { item.operationType == ActionOperationType.EVENT }?.let { item.leftSide to it.value },
                )
                is InScopeBlock -> raisedIn(item.body.items)
                is WithBlock -> raisedIn(item.body.items)
            }
        }

    /** The first event condition of [condition], with its clauses flattened in order. */
    private fun firstEvent(condition: Condition): EventCondition? =
        when (condition) {
            is EventCondition -> condition
            is CompositeCondition -> condition.clauses.firstNotNullOfOrNull(::firstEvent)
            else -> null
        }

    /** The catalog of [domains] for the one [machine], attached to `r`, without event definitions. */
    private fun build(domains: List<String>, machine: StateMachine): EventCatalog =
        EventCatalogBuilder.build(domains, listOf(ListeningMachine("r", FILE, machine)), null, emptyList(), SOURCES)

    /** A machine with [states] and [transitions]. */
    private fun machine(states: List<State> = listOf(state("a")), transitions: List<TransitionLike> = emptyList()): StateMachine =
        StateMachine("Robot", states.toMutableList(), transitions.toMutableList(), mutableListOf())

    /** The state [name] with the [entry] and [doing] actions and the [inner] states. */
    private fun state(name: String, entry: List<ActionItem> = emptyList(), doing: List<ActionItem> = emptyList(), inner: List<State> = emptyList()): State =
        State(name, ActionBlock(entry.toMutableList()), ActionBlock(doing.toMutableList()), inner.toMutableList())

    /** The action that raises the event [name] in [domain]. */
    private fun raise(domain: String, name: String): ActionRule = ActionRule(ActionOperationType.EVENT, domain, ValueActionRightSide(name))

    /** A condition of [clauses]. */
    private fun composite(vararg clauses: Condition): CompositeCondition = CompositeCondition(clauses.toList())

    private companion object {
        /** The file of the hand-made machines. */
        val FILE: Path = Path.of("robot.kstates")

        /** Texts no hand-made catalog reads. */
        val SOURCES = EventSources(TextSnapshot("type \"A\" \"\" {\n}\n", 0), null)
    }
}
