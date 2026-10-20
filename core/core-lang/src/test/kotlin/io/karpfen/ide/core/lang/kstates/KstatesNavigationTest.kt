package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.fixtures.at
import io.karpfen.ide.core.lang.fixtures.textAt
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** KStates go to definition and find references (spec §5) at concrete carets of the examples. */
class KstatesNavigationTest {

    private val probe = ServicesProbe()
    private val robot = ExampleProject.example(ExamplePaths.ROBOT_KSTATES)
    private val text = robot.text.text
    private val kmetaPath = ExampleProject.pathOf(ExamplePaths.ROBOT_KMETA)
    private val kmetaText = ExampleProject.textOf(ExamplePaths.ROBOT_KMETA)

    private fun definition(offset: Int, document: LanguageDocument = robot): Location = probe.definition(document, offset).single()

    @Test
    fun `state and macro references lead to their declarations in the file`() {
        val state = definition(after(text, "TRANSITION \"observe\" -> \"react to ob"))
        assertEquals(robot.path, state.path)
        assertEquals(at(text, "react to obstacle\" {"), state.range.start)
        assertEquals(robot.text.version, state.version)
        assertSame(robot.text.lineage, state.lineage)

        assertEquals(at(text, "get_closest_obstacle\" {"), definition(after(text, "MACRO(\"get_close")).range.start)
        assertEquals(at(text, "point_to_point_distance\" {"), definition(after(text, "@point_to_po")).range.start, "@m in EVAL")
        assertEquals(at(text, "observe\" {"), definition(after(text, "STATE \"obs")).range.start, "a declaration leads to itself")
    }

    @Test
    fun `types lead to the metamodel`() {
        val attached = definition(after(text, "ATTACHED TO \"Ro"))
        assertEquals(kmetaPath, attached.path)
        assertEquals("Robot", textAt(kmetaText, attached))
        assertEquals(0L, attached.version)
        assertSame(ExampleProject.analysis.file(kmetaPath)?.snapshot?.lineage, attached.lineage)

        assertEquals("TwoDObject", textAt(kmetaText, definition(after(text, "TAKES(\"p1\", \"TwoD"))))
        assertEquals("Obstacle", textAt(kmetaText, definition(after(text, "RETURNS(reference(\"Obs"))))
        assertTrue(probe.definition(robot, after(text, "RETURNS(\"num")).isEmpty(), "primitives have no declaration")
    }

    @Test
    fun `model path segments lead to member declarations in the metamodel`() {
        val position = definition(after(text, "SET(\"boundingBox->posi"))
        assertEquals(kmetaPath, position.path)
        assertEquals(at(kmetaText, "position\", \"Vector\""), position.range.start)

        val inEval = definition(after(text, "\$(boundingBox->position->") + 0)
        assertEquals(at(kmetaText, "x\", \"number\""), inEval.range.start)

        val relation = definition(after(text, "SETOBJ(\"self\", \"closest_ob"))
        assertEquals(at(kmetaText, "closest_obstacle\", \"Obstacle\""), relation.range.start)
    }

    @Test
    fun `bindings, parameters, self and event lead to their declarations`() {
        val binding = definition(after(text, "SET(\"direction->x\", EVAL { return \$(di"))
        assertEquals(robot.path, binding.path)
        assertEquals(at(text, "dir\" {"), binding.range.start)

        val parameter = definition(after(text, "@point_to_point_distance(\$(p"))
        assertEquals(at(text, "p1\", \"TwoDObject\""), parameter.range.start)

        val self = definition(after(text, "SETOBJ(\"se"))
        assertEquals("Robot", textAt(kmetaText, self))

        val drone = ExampleProject.example(ExamplePaths.DRONE_KSTATES)
        val droneText = drone.text.text
        val event = definition(at(droneText, "event->priority) >= 1", shift = 2), drone)
        assertEquals(ExampleProject.pathOf(ExamplePaths.DRONE_EVENTS), event.path)
        assertEquals("assignDelivery", textAt(ExampleProject.textOf(ExamplePaths.DRONE_EVENTS), event))
        assertTrue(probe.definition(drone, after(droneText, "SET(\"packageId\", EVAL { return \$(ev")).isEmpty(), "undetermined event")
    }

    @Test
    fun `event names lead to the event type of the events file`() {
        val start = definition(after(text, "EVENT(\"public\", \"sta"))

        assertEquals(ExampleProject.pathOf(ExamplePaths.ROBOT_EVENTS), start.path)
        assertEquals("start", textAt(ExampleProject.textOf(ExamplePaths.ROBOT_EVENTS), start))
    }

    @Test
    fun `while the file is broken a declaration only in the last good parse navigates with that parse's version`() {
        val editable = EditableText(text)
        val good = ExampleProject.document(robot.path, text, version = 0)
        val target = TextEdit.insert(0, "// a comment added above everything\n")
        editable.apply(listOf(target), EditOrigin.Programmatic("edit above"))
        val declaration = editable.snapshot.value.text.indexOf("STATE \"drive\" {")
        editable.apply(listOf(TextEdit.insert(declaration + "STATE ".length, "\"x\" ")), EditOrigin.Programmatic("break"))
        val current = editable.snapshot.value
        val broken = ExampleProject.document(robot.path, current.text, version = current.version, lastGoodParse = good.parse)
            .copy(text = current)
        val parse = assertNotNull(broken.parse)
        assertFalse(parse.isSyntaxClean, "precondition: the file is broken")
        assertTrue(parse.index.declarations(SymbolKind.STATE, "drive").isEmpty(), "precondition: the latest parse lost the declaration")

        val location = definition(after(current.text, "TRANSITION \"observe\" -> \"dri"), broken)

        assertEquals(0L, location.version)
        assertSame(current.lineage, location.lineage)
        val mapped = assertNotNull(editable.mapRange(location.range, assertNotNull(location.version), assertNotNull(location.lineage)))
        assertEquals("drive", current.substring(mapped))
        assertEquals(current.text.indexOf("\"x\" \"drive\"") + 5, mapped.start, "lands on the declaration")
    }

    @Test
    fun `references of states and macros are file-local, including embedded macro references`() {
        val states = probe.references(robot, after(text, "STATE \"obs"))
        assertEquals(1 + 5, states.size, "declaration and five transition strings")
        assertTrue(states.all { textAt(text, it) == "observe" })

        val macros = probe.references(robot, after(text, "MACRO \"point_to_point"))
        assertEquals(1 + 2, macros.size, "declaration and two @ references")
        assertTrue(macros.all { textAt(text, it) == "point_to_point_distance" })
    }

    @Test
    fun `references of bindings and parameters stay in their block`() {
        val binding = probe.references(robot, after(text, "AS \"di"))
        assertEquals(1 + 2, binding.size, "declaration and the two paths of its WITH block")
        assertTrue(binding.all { textAt(text, it) == "dir" })
        assertTrue(binding.all { it.range.start < at(text, "STATE \"react to wall\"") })

        val parameter = probe.references(robot, after(text, "\$(p1->posi") - "1->posi".length)
        assertEquals(1 + 2, parameter.size, "TAKES and the two uses in circular_bounding_box_distance")
        assertTrue(parameter.all { textAt(text, it) == "p1" && it.range.start < at(text, "MACRO \"point_to_point_distance\"") })
    }

    @Test
    fun `references of a member found from a path span the context`() {
        val references = probe.references(robot, after(text, "SET(\"d_closest_ob"))

        val here = references.filter { it.path == robot.path }.map { textAt(text, it) }.toSet()
        assertEquals(setOf("d_closest_obstacle"), here)
        assertTrue(references.count { it.path == robot.path } >= 6, "SET and EVAL paths")
        assertEquals(1, references.count { it.path == kmetaPath })
        assertEquals(1, references.count { it.path == ExampleProject.pathOf(ExamplePaths.ROBOT_KMODEL) })
    }

    @Test
    fun `references of an event name include the event type and every EVENT naming it`() {
        val references = probe.references(robot, after(text, "EVENT(\"public\", \"sta"))

        assertEquals(1, references.count { it.path == ExampleProject.pathOf(ExamplePaths.ROBOT_EVENTS) })
        assertEquals(1, references.count { it.path == robot.path })
    }
}
