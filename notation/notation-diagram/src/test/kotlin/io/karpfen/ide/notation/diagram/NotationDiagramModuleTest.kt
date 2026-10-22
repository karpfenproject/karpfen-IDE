package io.karpfen.ide.notation.diagram

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the phase-0 module stub [NotationDiagramModule]. */
class NotationDiagramModuleTest {
    @Test
    fun `module id matches the module directory`() {
        assertEquals("notation-diagram", NotationDiagramModule.ID)
    }
}
