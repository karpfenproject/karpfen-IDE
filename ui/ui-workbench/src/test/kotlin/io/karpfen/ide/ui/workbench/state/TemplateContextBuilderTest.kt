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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.workspace.analysis.ModelObject
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Tests that [TemplateContextBuilder] builds the template context from the contexts and the syntax indexes of the
 * analysis.
 */
class TemplateContextBuilderTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the context of the single robot example lists its types, objects and attached types`() = runTest(timeout = 60.seconds) {
        val root = dir.resolve("ws").toAbsolutePath().normalize()
        Examples.copyTo(root)
        val robot = root.resolve(DescriptorExamples.CONTEXT_DIRECTORY)
        withWorkbench(dir.resolve("settings")) {
            open(root)
            val context = TemplateContextBuilder.build(robot, workspace.contexts.value, workspace.analysis.value)

            assertEquals(robot, context.context?.directory)
            assertEquals(listOf("Vector", "TwoDObject", "Obstacle", "Wall", "Robot", "Room"), context.typeNames)
            assertEquals(ModelObject("APB 2101", "Room"), context.objects.first())
            assertEquals(ModelObject("turtle", "Robot"), context.objects[1])
            assertEquals(mapOf(robot.resolve("cleaning_robot.kstates") to "Robot"), context.stateMachineTypes)
            assertEquals(listOf("public"), context.eventDomains)
        }
    }

    @Test
    fun `a directory without a context gets only its path`() {
        val directory = dir.resolve("empty").toAbsolutePath()
        val context = TemplateContextBuilder.build(directory, ContextResolution.EMPTY, ProjectAnalysis.EMPTY)
        assertEquals(directory, context.directory)
        assertNull(context.context)
        assertEquals(emptyList(), context.typeNames)
    }
}
