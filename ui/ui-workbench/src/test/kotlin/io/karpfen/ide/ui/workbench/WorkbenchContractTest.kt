package io.karpfen.ide.ui.workbench

import io.karpfen.ide.ui.workbench.settings.IdeSettings
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Contract test of the complete ui-workbench values (no phase-1 implementation code is executed). */
class WorkbenchContractTest {

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
        assertEquals(".karpfen-ide", SettingsLocations.defaultDirectory().fileName.toString())
        assertEquals("logs", SettingsLocations.logDirectory().fileName.toString())
    }
}
