package io.karpfen.ide.bridges.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the phase-0 module stub [BridgeApiModule]. */
class BridgeApiModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("bridge-api", BridgeApiModule.ID)
    }
}
