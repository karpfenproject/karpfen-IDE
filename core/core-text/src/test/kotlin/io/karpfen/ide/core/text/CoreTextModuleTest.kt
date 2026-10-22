package io.karpfen.ide.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the phase-0 module stub [CoreTextModule]. */
class CoreTextModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("core-text", CoreTextModule.ID)
    }
}
