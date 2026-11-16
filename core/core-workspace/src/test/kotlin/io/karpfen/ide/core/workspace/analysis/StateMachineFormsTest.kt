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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the check of the form of each state machine a descriptor lists. An entry with `attachedTo` needs the attached
 * form, an entry without it the attachable form, and a definition listed twice is a warning. Each finding lies on the
 * file value of its entry.
 */
class StateMachineFormsTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    /** Writes the files of a small context with one attached and one attachable machine and the descriptor [entries]. */
    private fun project(entries: String): Path {
        Files.writeString(root.resolve("bulb.kmeta"), "type \"Bulb\" \"A bulb\" {\n    prop(\"lit\", \"boolean\")\n}\n")
        Files.writeString(root.resolve("bulb.kmodel"), "object \"b\" : \"Bulb\" {\n    \"lit\": false\n}\n")
        Files.writeString(
            root.resolve("attached.kstates"),
            "STATEMACHINE ATTACHED TO \"Bulb\" {\n    STATES {\n        INITIAL STATE \"off\" { }\n    }\n}\n",
        )
        Files.writeString(
            root.resolve("blink.kstates"),
            "STATEMACHINE \"blink\" ATTACHABLE TO \"Bulb\" {\n    STATES {\n        INITIAL STATE \"off\" { }\n    }\n}\n",
        )
        val descriptor = root.resolve("bulb.kenv.json")
        Files.writeString(
            descriptor,
            "{\n  \"metamodel\": \"bulb.kmeta\",\n  \"model\": \"bulb.kmodel\",\n  \"stateMachines\": [\n$entries\n  ]\n}\n",
        )
        return descriptor
    }

    /** The findings of the form check on [descriptor], each as severity and the text it lies on. */
    private suspend fun formFindings(descriptor: Path): List<Pair<Severity, String>> {
        val text = Files.readString(descriptor)
        val read = EnvironmentDescriptorReader().read(descriptor, text)
        val resolution = ContextResolver().resolve(WorkspaceScanner(emptySet()).scan(root), mapOf(descriptor to read))
        val analysis = analyzer().analyze(resolution, DiskSources(), generation = 1, languageFeatures = KstatesFeature.ALL)
        return analysis.diagnosticsFor(descriptor)
            .filter { it.code == WorkspaceDiagnosticCodes.DESCRIPTOR_STATE_MACHINE_FORM }
            .map { it.severity to text.substring(it.range.start, it.range.end) }
    }

    @Test
    fun `entries in the right form have no findings`() = runTest {
        val descriptor = project("    { \"file\": \"attached.kstates\", \"attachedTo\": \"b\" },\n    { \"file\": \"blink.kstates\" }")

        assertEquals(emptyList(), formFindings(descriptor))
    }

    @Test
    fun `the wrong form for each entry is an error and a definition listed twice a warning`() = runTest {
        val descriptor = project(
            "    { \"file\": \"attached.kstates\" },\n" +
                "    { \"file\": \"blink.kstates\", \"attachedTo\": \"b\" },\n" +
                "    { \"file\": \"blink.kstates\" },\n" +
                "    { \"file\": \"blink.kstates\" }",
        )

        assertEquals(
            listOf(
                Severity.ERROR to "attached.kstates",
                Severity.ERROR to "blink.kstates",
                Severity.WARNING to "blink.kstates",
            ),
            formFindings(descriptor),
        )
    }
}
