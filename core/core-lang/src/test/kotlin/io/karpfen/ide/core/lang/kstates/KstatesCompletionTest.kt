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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.fixtures.caret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers KStates completion by context at concrete carets of the examples and of small documents. The example project
 * has every optional language feature switched on, and the constructs of a feature that is off are not offered.
 */
class KstatesCompletionTest {

    private val probe = ServicesProbe()
    private val robot = ExampleProject.example(ExamplePaths.ROBOT_KSTATES)
    private val robotText = robot.text.text
    private val domainTypes = listOf("Obstacle", "Robot", "Room", "TwoDObject", "Vector", "Wall")
    private val robotMembers = listOf(
        "boundingBox", "closest_obstacle", "closest_wall", "d_closest_obstacle", "d_closest_wall", "direction", "log", "obstacles", "walls",
    )

    /** A document of the single-robot context (so the analysis applies) with [textWithCaret]. */
    private fun robotDocument(textWithCaret: String): Pair<LanguageDocument, Int> {
        val (text, offset) = caret(textWithCaret)
        return ExampleProject.document(robot.path, text, version = 7) to offset
    }

    private fun labelsIn(textWithCaret: String): List<String> =
        robotDocument(textWithCaret).let { (document, offset) -> probe.labels(document, offset) }

    private fun machine(body: String) = "STATEMACHINE ATTACHED TO \"Robot\" {\n$body\n}\n"

    @Test
    fun `structural keywords depend on the enclosing block`() {
        assertEquals(listOf("STATEMACHINE \"…\" ATTACHABLE TO \"…\" { }", "STATEMACHINE ATTACHED TO \"…\" { }"), labelsIn("‸"))
        assertEquals(listOf("MACROS { }", "STATES { }", "TRANSITIONS { }"), labelsIn(machine("  ‸")))
        val states = listOf("DEEP HISTORY STATE \"…\" { }", "INITIAL STATE \"…\" { }", "SHALLOW HISTORY STATE \"…\" { }", "STATE \"…\" { }")
        assertEquals(states, labelsIn(machine(" STATES {\n  ‸\n }")))
        assertEquals(
            (listOf("BREAKPOINT { }", "DO { }", "ENTRY { }", "TICK LIMIT { }") + states).sorted(),
            labelsIn(machine(" STATES {\n  STATE \"a\" {\n   ‸\n  }\n }")),
        )
        assertEquals(
            listOf("JOIN \"…\", \"…\" -> \"…\" { }", "SPLIT \"…\" -> \"…\", \"…\" { }", "TRANSITION \"…\" -> \"…\" { }"),
            labelsIn(machine(" STATES { }\n TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" { }\n  ‸\n }")),
        )
        assertEquals(listOf("NOT LOOPING"), labelsIn(machine(" TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" ‸ { }\n }")))
        assertEquals(listOf("BREAKPOINT { }", "CONDITION { }"), labelsIn(machine(" TRANSITIONS {\n  SPLIT \"a\" -> \"b\", \"c\" { ‸ }\n }")))
        assertEquals(
            listOf("EVAL { }", "EVENT(\"…\", \"…\")", "VALUE(\"…\")"),
            labelsIn(machine(" TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" { CONDITION { EVENT(\"d\", \"n\")\n ‸ } }\n }")),
        )
        assertEquals(listOf("MACRO \"…\" { }"), labelsIn(machine(" MACROS {\n  ‸\n }")))
        assertEquals(
            listOf("DEFINITION { EVAL { } }", "RETURNS(\"…\")", "TAKES(\"…\", \"…\")"),
            labelsIn(machine(" MACROS {\n  MACRO \"m\" {\n   TAKES(\"p\", \"Vector\")\n   ‸\n  }\n }")),
        )
        assertEquals(listOf("EVAL { }"), labelsIn(machine(" MACROS { MACRO \"m\" { RETURNS(\"number\") DEFINITION { ‸ } } }")))
    }

    @Test
    fun `breakpoint and tick limit blocks offer their words, and a timeout completes its target state`() {
        val state = { body: String -> machine(" STATES {\n  DEEP HISTORY STATE \"a\" {\n$body\n  }\n  STATE \"b\" { }\n }") }

        assertEquals(listOf("ENTRY", "EXIT"), labelsIn(state("   BREAKPOINT { ‸ }")))
        assertEquals(
            listOf("END", "START"),
            labelsIn(machine(" STATES { }\n TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" { BREAKPOINT { ‸ } }\n }")),
        )
        assertEquals(
            listOf("DELAY: … TICKS", "TIMEOUT: AFTER … TICKS TRANSITION TO \"…\""),
            labelsIn(state("   TICK LIMIT {\n    ‸\n   }")),
        )
        assertEquals(listOf("a", "b"), labelsIn(state("   TICK LIMIT { TIMEOUT: AFTER 3 TICKS TRANSITION TO \"‸\" }")))
        assertEquals(
            listOf("DO { }", "ENTRY { }").let { blocks -> blocks.all { it in labelsIn(state("   BREAKPOINT { ENTRY }\n   TICK LIMIT { DELAY: 2 TICKS }\n   ‸")) } },
            true,
        )
    }

    @Test
    fun `the constructs of the language features that are off are not offered`() {
        val off = ServicesProbe(ExampleProject.analysis.copy(languageFeatures = emptySet()))
        val labels = { textWithCaret: String -> robotDocument(textWithCaret).let { (document, offset) -> off.labels(document, offset) } }
        val stateBody = { body: String -> machine(" STATES {\n  STATE \"a\" {\n$body\n  }\n }") }

        assertEquals(listOf("STATES { }", "TRANSITIONS { }"), labels(machine("  ‸")))
        assertEquals(listOf("INITIAL STATE \"…\" { }", "STATE \"…\" { }"), labels(machine(" STATES {\n  ‸\n }")))
        assertEquals(listOf("DO { }", "ENTRY { }", "INITIAL STATE \"…\" { }", "STATE \"…\" { }"), labels(stateBody("   ‸")))
        assertEquals(emptyList(), labels(stateBody("   BREAKPOINT { ‸ }")))
        assertEquals(emptyList(), labels(stateBody("   TICK LIMIT {\n    ‸\n   }")))
        assertEquals(listOf("TRANSITION \"…\" -> \"…\" { }"), labels(machine(" STATES { }\n TRANSITIONS {\n  ‸\n }")))
        assertEquals(listOf("CONDITION { }"), labels(machine(" TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" { ‸ }\n }")))
        assertEquals(emptyList(), labels(machine(" MACROS {\n  ‸\n }")))
        val actions = labels(stateBody("   DO {\n    ‸\n   }"))
        assertTrue("SET(\"…\", …)" in actions && actions.none { it.startsWith("WITH") || it.startsWith("EXCHANGE") }, "$actions")
        assertEquals(listOf("STATEMACHINE ATTACHED TO \"…\" { }"), labels("‸"))
        assertEquals(listOf("EVAL { }"), labels(stateBody("   DO {\n    SET(\"log\", ‸)\n   }")))
    }

    @Test
    fun `a switched-on feature brings back exactly its own constructs`() {
        val histories = ServicesProbe(ExampleProject.analysis.copy(languageFeatures = setOf(KstatesFeature.HISTORY)))
        val (document, offset) = robotDocument(machine(" STATES {\n  ‸\n }"))

        assertEquals(
            listOf("DEEP HISTORY STATE \"…\" { }", "INITIAL STATE \"…\" { }", "SHALLOW HISTORY STATE \"…\" { }", "STATE \"…\" { }"),
            histories.labels(document, offset),
        )
        val (transitions, at) = robotDocument(machine(" STATES { }\n TRANSITIONS {\n  ‸\n }"))
        assertEquals(listOf("TRANSITION \"…\" -> \"…\" { }"), histories.labels(transitions, at))
    }

    @Test
    fun `action blocks offer the operations, EVENT, IF IN SCOPE and WITH, also after earlier actions and blocks`() {
        val actions = listOf(
            "APPEND(\"…\", …)", "APPENDOBJ(\"…\", \"…\", …)", "DROPLIST(\"…\")", "DROPOBJ(\"…\")", "EVENT(\"…\", \"…\")",
            "EXCHANGE(\"…\", …)", "IF IN SCOPE(\"…\") { }", "SET(\"…\", …)", "SETLIST(\"…\", …)", "SETOBJ(\"…\", \"…\", …)",
            "WITH … AS \"…\" { }",
        )
        val state = { body: String -> machine(" STATES {\n  STATE \"a\" {\n   DO {\n$body\n   }\n  }\n }") }

        assertEquals(actions, labelsIn(state("    ‸")))
        assertEquals(actions, labelsIn(state("    SET(\"log\", \"x\")\n    SE‸")))
        assertEquals(actions, labelsIn(state("    WITH MACRO(\"m\") AS \"x\" { SET(\"a\", \"b\") }\n    ‸")))
        assertEquals(actions, labelsIn(state("    SET(\"log\", \"x\") IF IN SCOPE(\"a\") {\n     ‸\n    }")))
        assertEquals(listOf("EVAL { }", "MACRO(\"…\")"), labelsIn(state("    SET(\"log\", ‸)")))

        val (document, offset) = robotDocument(state("    SET(\"log\", \"x\")\n    SE‸T"))
        val item = probe.complete(document, offset).single { it.label.startsWith("SET(") }
        assertEquals("SET", document.text.substring(item.replaceRange))
        assertEquals("SET(\"\", )", item.insertText)
        assertEquals(5, item.caretOffsetInInsert)
    }

    @Test
    fun `every KStates operation of KarpfenSyntax is offered, shaped by its arguments`() {
        val labels = KstatesKeywords.at(KstatesPosition.ACTIONS, KstatesFeature.entries.toSet()).map { it.label }
        val operations = labels.filter { label -> label.substringBefore('(') in KarpfenSyntax.KSTATES_OPERATIONS }

        assertEquals(KarpfenSyntax.KSTATES_OPERATIONS.toList(), operations.map { it.substringBefore('(') })
        operations.forEach { label ->
            val operation = label.substringBefore('(')
            val arguments = label.count { it == ',' } + 1
            val expected = when (operation) {
                in KarpfenSyntax.KSTATES_UNARY_OPERATIONS -> 1
                in KarpfenSyntax.KSTATES_RELATION_OPERATIONS -> 3
                else -> 2
            }
            assertEquals(expected, arguments, label)
        }
    }

    @Test
    fun `transition strings offer the states of the file`() {
        val states = listOf(
            "drive", "drive fast", "drive slow", "observe", "react to obstacle", "react to wall", "ready",
        )
        assertEquals(states, probe.labels(robot, after(robotText, "TRANSITION \"ready\" -> \"obs")))
        assertEquals(states, probe.labels(robot, after(robotText, "TRANSITION \"")))
        assertTrue(probe.complete(robot, after(robotText, "STATE \"obs")).isEmpty(), "a declaration")

        val car = ExampleProject.example(ExamplePaths.CAR_KSTATES)
        val carText = car.text.text
        assertTrue("checkBrakes" in probe.labels(car, after(carText, "SPLIT \"off\" -> \"checkBattery\", \"")))
        assertTrue("brakesReady" in probe.labels(car, after(carText, "JOIN \"batteryReady\", \"")))
        assertTrue("ready" in probe.labels(car, after(carText, "\"sensorsReady\" -> \"")))
    }

    @Test
    fun `macro names complete in MACRO calls and after @ in EVAL`() {
        val macros = listOf(
            "circular_bounding_box_distance", "get_closest_obstacle", "get_closest_wall", "opposite_direction_from_wall_with_variance",
            "opposite_direction_with_variance", "point_to_line_distance", "point_to_point_distance",
        )
        assertEquals(macros, probe.labels(robot, after(robotText, "MACRO(\"get_cl")))
        val atSign = probe.complete(robot, after(robotText, "@point_to_p"))
        assertEquals(macros, atSign.map { it.label }.sorted())
        assertEquals("point_to_point_distance", robot.text.substring(atSign.first().replaceRange))
        assertEquals(
            "macro point_to_point_distance(p1: Vector, p2: Vector): number",
            atSign.single { it.label == "point_to_point_distance" }.detail,
        )
        assertTrue(probe.complete(robot, after(robotText, "MACRO \"circ")).isEmpty(), "a declaration")
    }

    @Test
    fun `types complete after ATTACHED TO and in TAKES and RETURNS`() {
        assertEquals(domainTypes, probe.labels(robot, after(robotText, "ATTACHED TO \"")), "domain types only")
        assertEquals(domainTypes + listOf("boolean", "number", "string"), probe.labels(robot, after(robotText, "TAKES(\"p1\", \"")))
        assertEquals(domainTypes, probe.labels(robot, after(robotText, "RETURNS(reference(\"")))
        val listArgument = after(robotText, "TAKES(\"obstacles\", list(\"")
        assertEquals(domainTypes + listOf("boolean", "number", "string"), probe.labels(robot, listArgument))

        val (document, offset) = robotDocument(machine(" MACROS { MACRO \"m\" { RETURNS(‸) } }"))
        val items = probe.complete(document, offset)
        assertEquals("\"Vector\"", items.single { it.label == "Vector" }.insertText)
        assertTrue(items.any { it.label == "list(\"…\")" } && items.any { it.label == "reference(\"…\")" })
    }

    @Test
    fun `action paths start with the members of the attached type, self and event`() {
        val items = probe.complete(robot, after(robotText, "SET(\"d_closest_o"))

        assertEquals(robotMembers + listOf("event", "self"), items.sortedWith(compareBy({ it.sortKey }, { it.label })).map { it.label })
        val member = items.single { it.label == "boundingBox" }
        assertEquals(CompletionKind.PATH_SEGMENT, member.kind)
        assertEquals("TwoDObject", member.detail)
        assertEquals("has boundingBox: TwoDObject", member.documentation)
        assertEquals("d_closest_obstacle", robot.text.substring(member.replaceRange))
    }

    @Test
    fun `later segments are the members of the type reached, only the segment under the caret is replaced`() {
        val items = probe.complete(robot, after(robotText, "SET(\"boundingBox->position->"))

        assertEquals(listOf("x", "y"), items.map { it.label })
        assertEquals("x", robot.text.substring(items.first().replaceRange))
        val macroArgument = after(robotText, "MACRO(\"get_closest_obstacle\", \"boundingBox->")
        assertEquals(listOf("diameter", "position"), probe.labels(robot, macroArgument))
        assertEquals(listOf("diameter", "position"), probe.labels(robot, after(robotText, "\$(boundingBox->")))
        assertTrue(probe.complete(robot, after(robotText, "\$(obstacle->")).isEmpty(), "Python loop variables are not bound")
    }

    @Test
    fun `WITH bindings and macro parameters are typed by RETURNS and TAKES`() {
        val dir = after(robotText, "SET(\"direction->x\", EVAL { return \$(dir->")
        assertEquals(listOf("x", "y"), probe.labels(robot, dir))
        val first = probe.complete(robot, dir - "dir->".length)
        assertEquals(CompletionKind.BINDING, first.single { it.label == "dir" }.kind)
        assertEquals("dir", probe.labels(robot, dir - "dir->".length).first(), "bindings first")

        val parameter = after(robotText, "@point_to_point_distance(\$(p1->")
        assertEquals(listOf("diameter", "position"), probe.labels(robot, parameter))
        assertEquals(listOf("p1", "p2"), probe.labels(robot, parameter - "p1->".length).take(2))
    }

    @Test
    fun `event paths use the transition's event type, else every event type`() {
        val drone = ExampleProject.example(ExamplePaths.DRONE_KSTATES)
        val droneText = drone.text.text

        val guard = after(droneText, "EVAL { return \$(event->priority) >= 1") - "priority) >= 1".length
        assertEquals(listOf("destination", "packageId", "priority"), probe.labels(drone, guard))
        assertEquals(
            listOf("destination", "factor", "packageId", "priority"),
            probe.labels(drone, after(droneText, "SET(\"packageId\", EVAL { return \$(event->")),
            "in a state: all event types",
        )
        val scope = after(droneText, "IF IN SCOPE(\"event->")
        assertEquals(listOf("destination", "factor", "packageId", "priority"), probe.labels(drone, scope))
        assertEquals(listOf("x", "y"), probe.labels(drone, after(droneText, "return \$(event->destination->")))
    }

    @Test
    fun `the relation argument of SETOBJ offers the relations of the source path's type`() {
        val swarm = ExampleProject.example(ExamplePaths.SWARM_KSTATES)
        val text = swarm.text.text

        val items = probe.complete(swarm, after(text, "SETOBJ(\"self\", \"lea"))

        assertEquals(listOf("condemned", "drones", "formationCenter", "leader", "watchlist"), items.map { it.label }.sorted())
        assertTrue(items.all { it.kind == CompletionKind.RELATION })
        assertEquals("knows leader: Drone", items.single { it.label == "leader" }.documentation)
    }

    @Test
    fun `event names come from the events file and contain no domain type`() {
        val items = probe.complete(robot, after(robotText, "EVENT(\"public\", \"st"))

        assertEquals(listOf("setSpeed", "start", "teleport"), items.map { it.label }.sorted())
        assertTrue(items.none { it.label in domainTypes })
        assertTrue(items.all { it.kind == CompletionKind.EVENT })
        val action = after(robotText, "EVENT(\"public\", \"oh")
        assertEquals(listOf("setSpeed", "start", "teleport"), probe.labels(robot, action), "also as an action")
        assertEquals(listOf("public"), probe.labels(robot, after(robotText, "EVENT(\"pu")))
    }

    @Test
    fun `Python code, comments, values and declarations offer nothing`() {
        assertTrue(probe.complete(robot, after(robotText, "import ma")).isEmpty())
        assertTrue(probe.complete(robot, after(robotText, "// Update the dis")).isEmpty())
        assertTrue(probe.complete(robot, after(robotText, "APPEND(\"log\", \"Reac")).isEmpty())
        assertTrue(probe.complete(robot, after(robotText, "AS \"di")).isEmpty())
        assertTrue(probe.complete(robot, after(robotText, "TAKES(\"p")).isEmpty())
    }

    @Test
    fun `VALUE conditions and unterminated path strings complete paths`() {
        assertEquals(
            robotMembers + listOf("event", "self"),
            labelsIn(machine(" TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" { CONDITION { VALUE(\"‸\") } }\n }")),
        )
        assertEquals(listOf("diameter", "position"), labelsIn(machine(" STATES { STATE \"a\" { DO { SET(\"boundingBox->‸\n } } }")))
    }
}
