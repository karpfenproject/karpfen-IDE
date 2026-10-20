package io.karpfen.ide.runtime.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeProtocolModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("runtime-protocol", RuntimeProtocolModule.ID)
    }
}
