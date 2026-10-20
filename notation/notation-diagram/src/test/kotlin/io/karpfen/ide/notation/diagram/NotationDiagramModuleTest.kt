package io.karpfen.ide.notation.diagram

import kotlin.test.Test
import kotlin.test.assertEquals

class NotationDiagramModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("notation-diagram", NotationDiagramModule.ID)
    }
}
