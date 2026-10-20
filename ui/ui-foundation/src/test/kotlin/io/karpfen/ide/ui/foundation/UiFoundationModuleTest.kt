package io.karpfen.ide.ui.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

class UiFoundationModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("ui-foundation", UiFoundationModule.ID)
    }
}
