package io.karpfen.ide.ui.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

/** The module's identity: its id names the module directory. */
class UiFoundationModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("ui-foundation", UiFoundationModule.ID)
    }
}
