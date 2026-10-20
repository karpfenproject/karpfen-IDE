package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [ConflictBanner]: the banner per `EditorTab.conflict` (decision 43). */
class ConflictBannerTest {

    private val tab =
        EditorTab(Path.of("/ws/a.kstates").toAbsolutePath(), "a.kstates", "a.kstates", KarpfenLanguage.KSTATES, isDirty = true)

    @Test
    fun `modified offers Reload and Keep mine, deleted offers Close and Keep mine, both take the disk state with RELOAD`() {
        assertNull(ConflictBanner.of(tab))
        val modified = ConflictBanner.of(tab.copy(conflict = ExternalChangeKind.MODIFIED))!!
        assertEquals(
            listOf("Reload" to ConflictResolution.RELOAD, "Keep mine" to ConflictResolution.KEEP_MINE),
            modified.actions.map { it.label to it.resolution },
        )
        val deleted = ConflictBanner.of(tab.copy(conflict = ExternalChangeKind.DELETED))!!
        assertEquals(
            listOf("Close" to ConflictResolution.RELOAD, "Keep mine" to ConflictResolution.KEEP_MINE),
            deleted.actions.map { it.label to it.resolution },
        )
        assertEquals(true, deleted.message.contains("deleted"))
    }
}
