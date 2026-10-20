package io.karpfen.ide.bridges.api

import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeApiModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("bridge-api", BridgeApiModule.ID)
    }
}
