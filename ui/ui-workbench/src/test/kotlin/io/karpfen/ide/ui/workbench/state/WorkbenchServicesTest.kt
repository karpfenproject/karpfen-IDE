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

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import io.karpfen.ide.ui.workbench.output.RingOutputBuffer
import io.karpfen.ide.ui.workbench.testing.FakeOpenDocument
import io.karpfen.ide.ui.workbench.testing.TestClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The collaborators of the workbench keep one source per fact: a workspace's languages come from the workspace factory,
 * every editor gets the one clipboard the services are given, and the workbench shows the one output of the language
 * tools it is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchServicesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the services hold no language registry of their own beside the workspace factory's`() {
        val type = WorkbenchServices::class.java
        val held = type.constructors.flatMap { it.parameterTypes.toList() } + type.declaredFields.map { it.type }
        assertFalse(
            LanguageRegistry::class.java in held,
            "a workspace's languages come from its factory (Workspace.languages); a second registry could disagree",
        )
    }

    @Test
    fun `the production wiring gives every editor the clipboard it is given`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val clipboard = TestClipboard()
        val services = defaultWorkbenchServices(
            languageToolsOutput = RingOutputBuffer(),
            settingsDirectory = dir.resolve("settings"),
            backgroundDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clipboard = clipboard,
        )
        try {
            val editors = listOf("a.kmeta", "b.kmeta").map { name ->
                val document = FakeOpenDocument(dir.resolve(name).toAbsolutePath(), "type \"$name\" \"x\" { }\n", scope, dispatcher)
                val languageServices = defaultLanguageServices(MutableStateFlow(ProjectAnalysis.EMPTY))
                services.editorStateFactory.create(document.session, languageServices, scope, MutableStateFlow(EditorSettings()), services.editorKeymap)
            }

            editors[0].caret.select(0, 4)
            assertTrue(editors[0].execute(EditorActionIds.COPY))
            assertEquals("type", clipboard.content)
            clipboard.content = "pasted "
            editors[1].caret.moveTo(0)
            assertTrue(editors[1].execute(EditorActionIds.PASTE))
            assertTrue(editors[1].session.text.snapshot.value.text.startsWith("pasted type"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the production wiring hands the workbench the output of the language tools it is given`() {
        val output = RingOutputBuffer()

        val services = defaultWorkbenchServices(languageToolsOutput = output, settingsDirectory = dir.resolve("settings"))

        assertSame(output, services.languageToolsOutput, "the one buffer the application's logging fills")
    }
}
