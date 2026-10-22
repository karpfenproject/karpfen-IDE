package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.ServicesProbe
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.fixtures.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers KStates hover at concrete carets of the examples. */
class KstatesHoverTest {

    private val probe = ServicesProbe()
    private val robot = ExampleProject.example(ExamplePaths.ROBOT_KSTATES)
    private val text = robot.text.text

    private fun hover(offset: Int, document: LanguageDocument = robot): String = assertNotNull(probe.hover(document, offset)).text

    @Test
    fun `a state shows its hierarchy path`() {
        assertEquals("state observe > react to obstacle", hover(after(text, "TRANSITION \"observe\" -> \"react to ob")))
        assertEquals("state drive", hover(after(text, "STATE \"dri")))
        val range = assertNotNull(probe.hover(robot, after(text, "TRANSITION \"observe\" -> \"react to ob"))).range
        assertEquals("react to obstacle", text.substring(range.start, range.end))
    }

    @Test
    fun `a macro shows its TAKES and RETURNS signature`() {
        assertEquals(
            "macro circular_bounding_box_distance(p1: TwoDObject, p2: TwoDObject): number",
            hover(after(text, "MACRO(\"circular_b")),
        )
        assertEquals(
            "macro get_closest_obstacle(robot_position: Vector, obstacles: list(Obstacle)): reference(Obstacle)",
            hover(after(text, "MACRO \"get_closest_ob")),
        )
        assertEquals("macro point_to_point_distance(p1: Vector, p2: Vector): number", hover(after(text, "@point_to")))
    }

    @Test
    fun `a model path shows the type reached up to the segment under the caret`() {
        assertEquals("boundingBox->position: Vector\nhas position: Vector", hover(after(text, "SET(\"boundingBox->posi")))
        assertEquals("boundingBox: TwoDObject\nhas boundingBox: TwoDObject", hover(after(text, "SET(\"boundingB")))
        assertEquals("obstacles: list(Obstacle)\nknows obstacles: list(Obstacle)", hover(after(text, "\"boundingBox->position\", \"obst")))
        val range = assertNotNull(probe.hover(robot, after(text, "\$(boundingBox->posi"))).range
        assertEquals("position", text.substring(range.start, range.end))
        assertNull(probe.hover(robot, at(text, "\$(obstacle->", shift = 4)), "a Python loop variable")
    }

    @Test
    fun `bindings, parameters and roots show their types`() {
        assertEquals("dir: Vector\nWITH binding", hover(after(text, "EVAL { return \$(di")))
        assertEquals("p1: TwoDObject\nmacro parameter", hover(after(text, "@point_to_point_distance(\$(p")))
        assertEquals("self: Robot", hover(after(text, "SETOBJ(\"se")))
        assertEquals("binding dir: Vector", hover(after(text, "AS \"di")))
        assertEquals("parameter p1: TwoDObject", hover(after(text, "TAKES(\"p")))
    }

    @Test
    fun `event roots show the determined event type or every event type`() {
        val drone = ExampleProject.example(ExamplePaths.DRONE_KSTATES)
        val droneText = drone.text.text

        assertEquals("event: assignDelivery", hover(at(droneText, "event->priority) >= 1", shift = 2), drone))
        assertEquals("event->priority: number\nprop priority: number", hover(at(droneText, "priority) >= 1", shift = 2), drone))
        assertEquals(
            "event: any event (assignDelivery, setCruiseSpeed, recall)",
            hover(after(droneText, "SET(\"packageId\", EVAL { return \$(ev"), drone),
        )
    }

    @Test
    fun `types, event names and relations show the metamodel's texts`() {
        assertTrue(hover(after(text, "ATTACHED TO \"Ro")).startsWith("type Robot\nA cleaning robot"))
        assertEquals("type start\nSignal to start operating. No payload.", hover(after(text, "EVENT(\"public\", \"sta")))
        assertEquals("knows closest_obstacle: Obstacle", hover(after(text, "SETOBJ(\"self\", \"closest_ob")))
    }

    @Test
    fun `values, keywords, comments and Python code have no hover`() {
        assertNull(probe.hover(robot, after(text, "APPEND(\"log\", \"Reac")))
        assertNull(probe.hover(robot, after(text, "TRANSITI")))
        assertNull(probe.hover(robot, after(text, "// Update the")))
        assertNull(probe.hover(robot, after(text, "import ma")))
        assertNull(probe.hover(robot, after(text, "EVENT(\"pub")), "event domains")
    }
}
