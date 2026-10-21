package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
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
import kotlin.test.assertTrue

/** The collaborators of the workbench: one source per fact (decisions 46 and 47 of the work-package plan). */
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
}
