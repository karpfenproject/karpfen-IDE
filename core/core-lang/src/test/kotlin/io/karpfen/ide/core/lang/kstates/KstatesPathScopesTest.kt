package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.TypeCatalog
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The KStates path scope (spec §5): attached type, event type of the enclosing transition, WITH bindings and macro
 * parameters — read from the tokens at the caret and from the outline of a parse outcome, which must agree.
 */
class KstatesPathScopesTest {

    private val scopes = DefaultKstatesPathScopes(TypeCatalog())
    private val detector = CaretContextDetector()

    @Test
    fun `the scope at the caret has the attached type, the transition's event type, bindings and parameters`() {
        val drone = ExampleProject.example(ExamplePaths.DRONE_KSTATES)
        val text = drone.text.text
        val request = ExampleProject.request(drone)

        val guard = scopes.scopeAt(request, detector.detect(drone, after(text, "EVAL { return \$(event->priority) >= 1") - 4)).path
        assertEquals("Drone", guard.rootType)
        assertEquals("assignDelivery", guard.eventType)
        assertTrue(guard.bindings.isEmpty())

        val step = scopes.scopeAt(request, detector.detect(drone, after(text, "SET(\"position->x\", EVAL { return \$(p->"))).path
        assertEquals(mapOf("p" to PathType("GeoPoint", isList = false, isPrimitive = false)), step.bindings)
        assertNull(step.eventType)

        val macro = scopes.scopeAt(request, detector.detect(drone, after(text, "dx = \$(dest->"))).path
        assertEquals(
            mapOf(
                "origin" to PathType("GeoPoint", isList = false, isPrimitive = false),
                "dest" to PathType("GeoPoint", isList = false, isPrimitive = false),
                "speed" to PathType("number", isList = false, isPrimitive = true),
            ),
            macro.bindings,
        )
    }

    @Test
    fun `binding facts locate the binding's name in the request's text`() {
        val robot = ExampleProject.example(ExamplePaths.ROBOT_KSTATES)
        val text = robot.text.text

        val facts = scopes.scopeAt(ExampleProject.request(robot), detector.detect(robot, after(text, "return \$(dir->"))).facts

        val binding = facts.bindings.single()
        assertEquals("opposite_direction_with_variance", binding.macro)
        val declaration = assertNotNull(binding.declaration)
        assertEquals("dir", robot.text.substring(declaration.range))
        assertEquals(robot.text.version, declaration.version)
    }

    @Test
    fun `tokens and outline give the same scope at every model path of the examples`(reporter: TestReporter) {
        var compared = 0
        for (example in Examples.files(KarpfenLanguage.KSTATES)) {
            val document = ExampleProject.example(example.path)
            val request = ExampleProject.request(document)
            val source = assertNotNull(DocumentDeclarations(document).latest())
            for (reference in source.outcome.index.referencesOf(ReferenceKind.MODEL_PATH)) {
                val offset = reference.range.start
                val fromTokens = scopes.scopeAt(request, detector.detect(document, offset))
                val fromOutline = scopes.scopeIn(request, source, offset)
                val where = "${example.path} @$offset (${reference.name})"
                assertEquals(fromTokens.path, fromOutline.path, where)
                assertEquals(fromTokens.facts.copy(bindings = emptyList()), fromOutline.facts.copy(bindings = emptyList()), where)
                val bindings = { scope: KstatesScope -> scope.facts.bindings.map { it.name to it.macro } }
                assertEquals(bindings(fromTokens), bindings(fromOutline), where)
                compared++
            }
        }
        assertTrue(compared > 200)
        reporter.publishEntry("model paths compared", "$compared")
    }

    @Test
    fun `macro signatures read RETURNS and TAKES types`() {
        val robot = ExampleProject.example(ExamplePaths.ROBOT_KSTATES)
        val signatures = MacroSignatures(DocumentDeclarations(robot).located().map { it.outcome.index })

        assertEquals(PathType("Obstacle", isList = false, isPrimitive = false), signatures.returns("get_closest_obstacle"))
        assertEquals(PathType("number", isList = false, isPrimitive = true), signatures.returns("point_to_point_distance"))
        assertEquals(
            listOf("robot_position" to PathType("Vector", false, false), "obstacles" to PathType("Obstacle", true, false)),
            signatures.parameters("get_closest_obstacle"),
        )
        assertNull(signatures.returns("unknown"))
        assertTrue(signatures.parameters("unknown").isEmpty())
    }
}
