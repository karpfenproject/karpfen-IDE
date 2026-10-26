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
package io.karpfen.ide.core.lang.symbols

import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the shared type catalog: document, domain and event types with members and declaration locations, the
 * memoized conversion per analysis, the partial metamodel of a metamodel with errors, and the syntax fallback of a
 * metamodel that was not read.
 */
class TypeCatalogTest {

    private val catalog = TypeCatalog()
    private val robotKstates = ExampleProject.request(ExampleProject.example(ExamplePaths.ROBOT_KSTATES))

    @Test
    fun `a KStates document sees the domain types, then the event types of the events file`() {
        val types = catalog.types(robotKstates)

        assertEquals(
            listOf("Vector", "TwoDObject", "Obstacle", "Wall", "Robot", "Room", "start", "setSpeed", "teleport"),
            types.map { it.name },
        )
        assertEquals(List(6) { TypeOrigin.DOMAIN } + List(3) { TypeOrigin.EVENTS }, types.map { it.origin })
        assertEquals(types, catalog.contextTypes(robotKstates))
    }

    @Test
    fun `event types contain no domain type although dsl-tools adds the base types to the events metamodel`() {
        val context = assertNotNull(robotKstates.contextAnalysis)
        assertTrue(assertNotNull(context.events).types.any { it.name == "Robot" }, "precondition: base types in the events metamodel")

        val events = catalog.types(robotKstates).filter { it.origin == TypeOrigin.EVENTS }.map { it.name }

        assertEquals(listOf("start", "setSpeed", "teleport"), events)
    }

    @Test
    fun `members keep the file's order, kinds, list flags and primitive types`() {
        val robot = assertNotNull(catalog.type(robotKstates, "Robot"))

        assertEquals(
            listOf(
                "prop log: list(string)",
                "prop d_closest_obstacle: number",
                "prop d_closest_wall: number",
                "has boundingBox: TwoDObject",
                "has direction: Vector",
                "knows obstacles: list(Obstacle)",
                "knows walls: list(Wall)",
                "knows closest_obstacle: Obstacle",
                "knows closest_wall: Wall",
            ),
            robot.members.map { it.signature },
        )
        assertEquals("A cleaning robot that can move around the room and log its actions", robot.description)
        assertTrue(robot.hoverText.startsWith("type Robot\nA cleaning robot"))
    }

    @Test
    fun `declarations point into the metamodel file with its analyzed snapshot`() {
        val file = assertNotNull(ExampleProject.analysis.file(ExampleProject.pathOf(ExamplePaths.ROBOT_KMETA)))
        val vector = assertNotNull(catalog.type(robotKstates, "Vector"))

        val declaration = assertNotNull(vector.declaration)
        assertEquals(file.path, declaration.path)
        assertEquals("Vector", file.snapshot.substring(declaration.range))
        assertEquals(0L, declaration.version)
        assertSame(file.snapshot.lineage, declaration.lineage)
        val x = assertNotNull(vector.member("x")?.declaration)
        assertEquals("x", file.snapshot.substring(x.range))
        val factor = assertNotNull(catalog.type(robotKstates, "setSpeed")?.member("factor")?.declaration)
        assertEquals(ExampleProject.pathOf(ExamplePaths.ROBOT_EVENTS), factor.path)
    }

    @Test
    fun `a KMeta document's own types come first, from its parse, before the converted ones`() {
        val document = ExampleProject.example(ExamplePaths.ROBOT_KMETA) { it.replace("type \"Room\"", "type \"Hall\"") }
        val request = ExampleProject.request(document)

        val types = catalog.types(request)

        assertEquals(TypeOrigin.DOCUMENT, types.first { it.name == "Vector" }.origin)
        assertEquals(TypeOrigin.DOCUMENT, types.first { it.name == "Hall" }.origin)
        assertEquals(TypeOrigin.DOMAIN, types.first { it.name == "Room" }.origin, "only in the analysis")
        val hall = types.first { it.name == "Hall" }
        assertEquals(document.text.version, hall.declaration?.version)
        assertSame(document.text.lineage, hall.declaration?.lineage)
        assertEquals("A room that contains a robot and obstacles", hall.description)
        assertEquals(
            listOf("has robot: Robot", "has obstacles: list(Obstacle)", "has walls: list(Wall)"),
            hall.members.map { it.signature },
        )
    }

    @Test
    fun `in the events file the event types are the document's own and the domain types follow`() {
        val request = ExampleProject.request(ExampleProject.example(ExamplePaths.ROBOT_EVENTS))

        val types = catalog.types(request)

        assertEquals(listOf("start", "setSpeed", "teleport"), types.filter { it.origin == TypeOrigin.DOCUMENT }.map { it.name })
        assertTrue(types.any { it.name == "Vector" && it.origin == TypeOrigin.DOMAIN })
        assertTrue(request.isEventsDocument)
    }

    @Test
    fun `the conversion is memoized per context analysis of the latest project analysis`() {
        val first = catalog.contextTypes(robotKstates)
        assertSame(first, catalog.contextTypes(robotKstates))

        val reanalyzed = ExampleProject.analyze(Examples.contexts(), generation = 2)
        val fresh = catalog.contextTypes(ExampleProject.request(robotKstates.document, reanalyzed))

        assertNotSame(first, fresh)
        assertEquals(first.map { it.name }, fresh.map { it.name })
    }

    @Test
    fun `a metamodel with an error still gives its types, without the member dsl-tools could not read`() {
        val robotContext = Examples.contexts().single { it.directory == ExamplePaths.ROBOT_DIR }
        val files = robotContext.files.map { file ->
            if (file.path == ExamplePaths.ROBOT_KMETA) file.copy(text = file.text.replace(WALL_DISTANCE, MISSPELLED_WALL_DISTANCE)) else file
        }
        val analysis = ExampleProject.analyze(listOf(robotContext.copy(files = files)))
        val metamodelPath = ExampleProject.pathOf(ExamplePaths.ROBOT_KMETA)
        assertEquals(listOf("kmeta.unknown-property-type"), analysis.diagnosticsFor(metamodelPath).map { it.code })

        val types = catalog.types(ExampleProject.request(robotKstates.document, analysis))

        assertEquals(robotKstates.let(catalog::types).map { it.name to it.origin }, types.map { it.name to it.origin })
        val robot = types.first { it.name == "Robot" }
        assertEquals(null, robot.member("d_closest_wall"), "left out of the partial metamodel")
        assertEquals("prop d_closest_obstacle: number", robot.member("d_closest_obstacle")?.signature)
    }

    @Test
    fun `a metamodel that was not read falls back to its file's syntax`() {
        val context = assertNotNull(robotKstates.contextAnalysis)
        val broken = robotKstates.copy(contextAnalysis = context.copy(metamodel = null, events = null))

        val types = catalog.types(broken)

        assertEquals(robotKstates.let(catalog::types).map { it.name to it.origin }, types.map { it.name to it.origin })
        assertEquals("knows obstacles: list(Obstacle)", types.first { it.name == "Robot" }.member("obstacles")?.signature)
        assertEquals("A vector in 2D space", types.first { it.name == "Vector" }.description)
    }

    @Test
    fun `documents outside a context see only their own types`() {
        val text = "type \"A\" \"d\" { knows(\"b\", list(\"B\")) }"
        val document = ExampleProject.document(ExampleProject.ROOT.resolve("loose/a.kmeta"), text)
        val request = ExampleProject.request(document)

        val types = catalog.types(request)

        assertEquals(listOf("A"), types.map { it.name })
        val member = types.single().members.single()
        assertEquals(MemberInfo("b", MemberKind.KNOWS, "B", isList = true, declaration = member.declaration), member)
        assertEquals(TextRange(22, 23), member.declaration?.range)
    }

    private companion object {
        /** A property declaration of the robot's metamodel. */
        const val WALL_DISTANCE = "prop(\"d_closest_wall\", \"number\")"

        /** [WALL_DISTANCE] with a misspelled type, an error dsl-tools reports at the type. */
        const val MISSPELLED_WALL_DISTANCE = "prop(\"d_closest_wall\", \"nmber\")"
    }
}
