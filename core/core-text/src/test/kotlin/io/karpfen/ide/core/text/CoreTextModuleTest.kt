package io.karpfen.ide.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreTextModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("core-text", CoreTextModule.ID)
    }
}
