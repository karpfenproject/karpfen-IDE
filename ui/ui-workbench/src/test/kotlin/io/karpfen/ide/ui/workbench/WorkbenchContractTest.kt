package io.karpfen.ide.ui.workbench

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.layout.LayoutUiState
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.outline.OutlineUiState
import io.karpfen.ide.ui.workbench.problems.ProblemItem
import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import io.karpfen.ide.ui.workbench.settings.ThemeMode
import io.karpfen.ide.ui.workbench.settings.ToolWindowLayout
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the public values of ui-workbench: the settings with their recent folders, location and layout, the answers
 * dialogs accept, and the versioned locations that problems and outline nodes navigate to.
 */
class WorkbenchContractTest {

    private val path: Path = Path.of("/ws/robot.kstates").toAbsolutePath()

    @Test
    fun `recent folders are most recent first, unique and capped`() {
        var settings = IdeSettings()
        (1..12).forEach { settings = settings.withRecentFolder("/f$it") }
        settings = settings.withRecentFolder("/f5")

        assertEquals(IdeSettings.MAX_RECENT_FOLDERS, settings.recentFolders.size)
        assertEquals(listOf("/f5", "/f12", "/f11"), settings.recentFolders.take(3))
        assertFailsWith<IllegalArgumentException> { IdeSettings(recentFolders = List(11) { "/f$it" }) }
    }

    @Test
    fun `settings live in the user's karpfen-ide directory`() {
        assertNull(System.getProperty(SettingsLocations.HOME_PROPERTY), "the test JVM must not set the IDE home")
        assertEquals(".karpfen-ide", SettingsLocations.defaultDirectory().fileName.toString())
        assertEquals("logs", SettingsLocations.logDirectory().fileName.toString())
    }

    @Test
    fun `the layout round-trips through the settings by tool-window name`() {
        val layout = LayoutUiState().let {
            it.copy(toolWindows = it.toolWindows + (ToolWindowId.PROBLEMS to ToolWindowLayout(visible = false, size = 120f)), theme = ThemeMode.DARK)
        }
        val settings = layout.applyTo(IdeSettings(toolWindows = mapOf("RUNTIME" to ToolWindowLayout(size = 300f))))

        assertEquals(ToolWindowLayout(visible = false, size = 120f), settings.toolWindows["PROBLEMS"])
        assertTrue("RUNTIME" in settings.toolWindows, "entries of unknown tool windows are kept")
        assertEquals(layout, LayoutUiState.of(settings))
        assertEquals(ToolWindowId.OUTLINE.defaultLayout, LayoutUiState.of(IdeSettings()).toolWindow(ToolWindowId.OUTLINE))
        assertFailsWith<IllegalArgumentException> { ToolWindowLayout(size = 0f) }
    }

    @Test
    fun `dialogs accept exactly their answers`() {
        assertTrue(DialogRequest.UnsavedChanges(listOf(path)).accepts(DialogResponse.Discard))
        assertFalse(DialogRequest.Rename(path).accepts(DialogResponse.Save))
        assertTrue(DialogRequest.NewFile(path.parent, FileTemplateKind.KSTATES).accepts(DialogResponse.Text("b")))
        assertTrue(DialogRequest.ConfirmDelete(path, toTrash = true).accepts(DialogResponse.Cancel))
        assertFalse(DialogRequest.Error("t", "m").accepts(DialogResponse.Cancel))
        assertEquals(".kenv.json", FileTemplateKind.ENVIRONMENT.suffix)
    }

    @Test
    fun `problems and outline nodes navigate with versioned locations`() {
        val text = TextSnapshot("STATE", 7)
        val diagnostic = Diagnostic(TextRange(0, 5), Severity.ERROR, "m", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
        val problem = ProblemItem(path, diagnostic, line = 1, column = 1, version = 7, lineage = text.lineage)

        assertEquals(7L, problem.location.version)
        assertTrue(problem.location.lineage === text.lineage)
        assertFailsWith<IllegalArgumentException> { ProblemItem(path, diagnostic, 1, 1, version = null, lineage = text.lineage) }

        val node = OutlineNode(SymbolKind.STATE, "a", null, TextRange(0, 5), TextRange(1, 2), emptyList())
        assertNull(OutlineUiState().locationOf(node))
        val outline = OutlineUiState(path, version = 5, lineage = text.lineage, nodes = listOf(node), isStale = true)
        assertEquals(TextRange(1, 2), outline.locationOf(node)?.range)
        assertEquals(5L, outline.locationOf(node)?.version)
    }
}
