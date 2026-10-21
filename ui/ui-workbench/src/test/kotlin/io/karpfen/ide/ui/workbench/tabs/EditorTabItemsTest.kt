package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TabItem
import io.karpfen.ide.ui.workbench.tabs.view.tabItemOf
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The tab-strip items of the editor tabs: a tab in conflict is marked on the strip, also when it is not active. */
class EditorTabItemsTest {

    private val tab = EditorTab(
        path = Path.of("/ws/robot.kstates").toAbsolutePath(),
        title = "robot.kstates",
        tooltip = "robot.kstates",
        language = KarpfenLanguage.KSTATES,
        isDirty = true,
    )

    @Test
    fun `a tab without a conflict shows its title, tooltip, dirty dot and language icon`() {
        assertEquals(TabItem(tab.path.toString(), "robot.kstates", "robot.kstates", isDirty = true, KarpfenIcons.Kstates), tabItemOf(tab))
    }

    @Test
    fun `a tab in conflict shows the warning icon and the banner's message in its tooltip`() {
        val modified = tabItemOf(tab.copy(conflict = ExternalChangeKind.MODIFIED))
        val deleted = tabItemOf(tab.copy(conflict = ExternalChangeKind.DELETED))

        assertSame(KarpfenIcons.Warning, modified.icon)
        assertSame(KarpfenIcons.Warning, deleted.icon)
        assertEquals("robot.kstates — robot.kstates changed on disk while it has unsaved changes.", modified.tooltip)
        assertEquals("robot.kstates — robot.kstates was deleted on disk.", deleted.tooltip)
        assertEquals("robot.kstates", modified.title)
    }
}
