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
package io.karpfen.ide.core.lang.navigation

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers the files a cross-file reference search sees and how its contributors are asked. */
class ContextReferencesTest {

    @Test
    fun `the requesting document replaces its analyzed file, the other context files keep their analyzed outcome`() {
        val document = ExampleProject.example(ExamplePaths.ROBOT_KMETA) { it + "\n// edited\n" }
        val scope = ReferenceScope.of(ExampleProject.request(document))

        assertEquals(4, scope.files.size)
        val own = scope.files.first()
        assertEquals(document.path, own.source.path)
        assertSame(document.parse, own.source.outcome)
        assertEquals(document.text.text, own.text)
        val model = scope.files.single { it.source.path == ExampleProject.pathOf(ExamplePaths.ROBOT_KMODEL) }
        val analyzed = ExampleProject.analysis.file(model.source.path)
        assertSame(analyzed?.parse, model.source.outcome)
        assertSame(analyzed?.snapshot?.lineage, model.source.lineage)
    }

    @Test
    fun `event types are the types of the events file`() {
        val scope = ReferenceScope.of(ExampleProject.request(ExampleProject.example(ExamplePaths.ROBOT_KSTATES)))

        assertTrue(scope.isEventType("setSpeed"))
        assertFalse(scope.isEventType("Vector"))
    }

    @Test
    fun `a document outside every context is searched alone and an unparsed one not at all`() {
        val loose = ExampleProject.document(Path.of("/elsewhere/a.kmeta"), "type \"A\" \"d\" { }")
        assertEquals(listOf(loose.path), ReferenceScope.of(ExampleProject.request(loose)).files.map { it.source.path })

        val unparsed = loose.copy(parse = null, lastGoodParse = null)
        assertTrue(ReferenceScope.of(ExampleProject.request(unparsed)).files.isEmpty())
    }

    @Test
    fun `every contributor is asked about every file and duplicates are dropped`() {
        val asked = ArrayList<Path>()
        val location = Location(Path.of("/x"), TextRange(0, 1))
        val references = ContextReferences(
            listOf(
                ReferenceContributor { _, file, _ ->
                    asked.add(file.source.path)
                    listOf(location)
                },
                ReferenceContributor { _, _, _ -> listOf(location) },
            ),
        )
        val request = ExampleProject.request(ExampleProject.example(ExamplePaths.ROBOT_KSTATES))

        assertEquals(listOf(location), references.find(ContextSymbol.Type("Vector"), request))
        assertEquals(4, asked.size, "metamodel, events, model and state machine")
    }

    @Test
    fun `type references are found by name in files of every language`() {
        val request = ExampleProject.request(ExampleProject.example(ExamplePaths.ROBOT_KSTATES))
        val scope = ReferenceScope.of(request)
        val contributor = TypeReferences()

        val byFile = scope.files.associate { file ->
            file.source.path.fileName.toString() to contributor.references(ContextSymbol.Type("Obstacle"), file, scope).size
        }

        assertEquals(
            mapOf("cleaning_robot.kstates" to 2, "cleaning_robot.kmeta" to 3, "EVENTS.kmeta" to 0, "cleaning_robot.kmodel" to 2),
            byFile,
        )
        assertNull(scope.files.firstOrNull { contributor.references(ContextSymbol.Member("Robot", "log"), it, scope).isNotEmpty() })
    }
}
