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

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import io.karpfen.ide.ui.workbench.templates.FileTemplates
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.switchOnLanguageFeatures
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests new files from templates. It covers their names, their contents for each template context, revealing and
 * opening them, and failures.
 */
class CreateFileFlowTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val robot: Path get() = root.resolve("karpfen-runtime/single_robot_example")

    private fun scenario(block: suspend TestWorkbench.() -> Unit) = runTest(timeout = 60.seconds) {
        Examples.copyTo(root)
        switchOnLanguageFeatures(root)
        Files.createDirectories(root.resolve("empty"))
        withWorkbench(dir.resolve("settings")) {
            open(root)
            block()
        }
    }

    private fun TestWorkbench.create(directory: Path, kind: FileTemplateKind, name: String): Path {
        val job = state.createFile(directory, kind)
        val asked = answer(DialogRequest.NewFile::class, DialogResponse.Text(name))
        assertEquals(DialogRequest.NewFile(directory, kind), asked)
        assertTrue(job.isCompleted)
        return directory.resolve(FileTemplates().fileName(kind, name))
    }

    private fun TestWorkbench.diagnosticsOf(path: Path) = workspace.problems.value.of(path)?.diagnostics.orEmpty()

    @Test
    fun `each template creates a file that is revealed and opened`() = scenario {
        val empty = root.resolve("empty")
        FileTemplateKind.entries.forEach { kind ->
            val created = create(empty, kind, "robot")
            assertTrue(Files.exists(created), "$kind")
            assertEquals(created, state.projectTree.selected.value)
            assertTrue(created.parent in state.projectTree.expanded.value)
            assertTrue(state.projectTree.tree.value?.find(created) != null, "the workspace was refreshed")
        }
        assertEquals(
            listOf("robot.kmeta", "robot.kmodel", "robot.kstates", "robot.kenv.json"),
            state.tabs.tabs.value.map { it.title },
            "karpfen files and descriptors open in tabs",
        )
        assertEquals(emptyList(), fileOperations.openedExternally)

        workspace.awaitAnalysis()
        settle()
        listOf("robot.kmeta", "robot.kmodel", "robot.kstates", "robot.kenv.json").forEach { name ->
            assertEquals(emptyList(), diagnosticsOf(empty.resolve(name)).filter { it.severity == Severity.ERROR }, name)
        }
    }

    @Test
    fun `templates in a directory with an implicit context use its files and types`() = scenario {
        val descriptor = create(robot, FileTemplateKind.ENVIRONMENT, "single_robot")
        val written = Json.decodeFromString(EnvironmentDescriptor.serializer(), Files.readString(descriptor))
        assertEquals("cleaning_robot.kmeta", written.metamodel)
        assertEquals("EVENTS.kmeta", written.events)
        assertEquals("cleaning_robot.kmodel", written.model)
        val attached = EnvironmentDescriptor.StateMachineEntry("cleaning_robot.kstates", DescriptorExamples.ATTACHED_TO)
        assertEquals(listOf(attached), written.stateMachines)
        assertEquals(EnvironmentDescriptor.ObserveSpec("*", listOf("public")), written.observe, "the domains of the state machine")
        assertEquals(descriptor, state.tabs.active.value, "the new descriptor opens in the IDE")

        val model = create(robot, FileTemplateKind.KMODEL, "second")
        assertTrue(Files.readString(model).contains("\"second\":\"Vector\""), "the context's first domain type")
        val machine = create(robot, FileTemplateKind.KSTATES, "second")
        assertTrue(Files.readString(machine).contains("ATTACHED TO \"Vector\""))

        workspace.awaitAnalysis()
        settle()
        val context = workspace.contexts.value.contexts.single { it.directory == robot }
        assertEquals(descriptor, context.descriptor, "the new descriptor defines the directory's context")
        assertEquals(emptyList(), diagnosticsOf(descriptor), "it names existing files only")
        listOf(robot.resolve("cleaning_robot.kmodel"), robot.resolve("cleaning_robot.kstates")).forEach {
            assertTrue(it in context, "$it stays in the context")
            val dslTools = diagnosticsOf(it).filter { d -> d.source == DiagnosticSource.DSL_TOOLS }
            assertEquals(emptyList(), dslTools, "$it reads without findings")
        }
    }

    @Test
    fun `the KModel and KStates templates read without findings in an implicit context`() = scenario {
        val model = create(robot, FileTemplateKind.KMODEL, "another")
        val machine = create(robot, FileTemplateKind.KSTATES, "another")
        workspace.awaitAnalysis()
        settle()
        val context = assertNotNull(workspace.contexts.value.contexts.firstOrNull { model in it })
        assertTrue(machine in context)
        assertEquals(emptyList(), diagnosticsOf(model) + diagnosticsOf(machine), "parse, read, no findings")
    }

    @Test
    fun `cancelling creates nothing and an existing name becomes an error`() = scenario {
        val empty = root.resolve("empty")
        val cancelled = state.createFile(empty, FileTemplateKind.KMETA)
        answer(DialogRequest.NewFile::class, DialogResponse.Cancel)
        assertTrue(cancelled.isCompleted)
        assertEquals(0L, Files.list(empty).use { it.count() })

        val existing = state.createFile(robot, FileTemplateKind.KMETA)
        answer(DialogRequest.NewFile::class, DialogResponse.Text("cleaning_robot.kmeta"))
        val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)
        assertTrue(existing.isCompleted)
        assertTrue(error.message.contains("already exists"), error.message)
        assertNull(state.tabs.active.value)
    }
}
