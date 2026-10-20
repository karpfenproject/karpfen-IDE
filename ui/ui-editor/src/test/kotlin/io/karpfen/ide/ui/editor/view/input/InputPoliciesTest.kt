package io.karpfen.ide.ui.editor.view.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The small input policies of the view: click counting, the drag auto-scroll step and the typed-key gate. */
class InputPoliciesTest {

    @Test
    fun `clicks in quick succession at one place count up`() {
        val counter = ClickCounter(timeoutMillis = 300, slop = 4f)

        assertEquals(1, counter.press(1000, 10f, 10f))
        assertEquals(2, counter.press(1200, 12f, 9f))
        assertEquals(3, counter.press(1400, 10f, 10f))
        assertEquals(4, counter.press(1500, 10f, 10f), "the editor state treats more than three as three")
        assertEquals(1, counter.press(2000, 10f, 10f), "too late")
        assertEquals(1, counter.press(2100, 30f, 10f), "too far")
    }

    @Test
    fun `auto-scroll steps by the distance outside the area, limited`() {
        assertEquals(0f to 0f, AutoScroll.step(50f, 50f, 100f, 100f, 40f))
        assertEquals(0f to -10f, AutoScroll.step(50f, -10f, 100f, 100f, 40f))
        assertEquals(0f to 40f, AutoScroll.step(50f, 300f, 100f, 100f, 40f))
        assertEquals(-5f to 25f, AutoScroll.step(-5f, 125f, 100f, 100f, 40f))
        assertEquals(40f to 0f, AutoScroll.step(1000f, 100f, 100f, 100f, 40f), "the edge itself is inside")
    }

    @Test
    fun `the typed event of a handled key is swallowed, others pass`() {
        val gate = TypedKeyGate()

        gate.keyDown(handled = true)
        assertTrue(gate.typed())
        assertFalse(gate.typed(), "only one typed event per press")
        gate.keyDown(handled = false)
        assertFalse(gate.typed())
        gate.keyDown(handled = true)
        gate.keyDown(handled = false)
        assertFalse(gate.typed(), "a newer unhandled press decides")
        assertFalse(TypedKeyGate().typed(), "input-method commits without a key press pass")
    }
}
