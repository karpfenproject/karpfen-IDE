package io.karpfen.ide.ui.workbench.dialog

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [DialogFocusOrder]: Tab and Shift+Tab cycle through a dialog's focus stops, skipping disabled buttons. */
class DialogFocusOrderTest {

    @Test
    fun `Tab and Shift+Tab cycle through every stop and wrap around`() {
        val order = DialogFocusOrder(listOf(true, true, true, true))

        assertEquals(listOf(1, 2, 3, 0), (0..3).map(order::next))
        assertEquals(listOf(3, 0, 1, 2), (0..3).map(order::previous))
    }

    @Test
    fun `disabled buttons are skipped`() {
        val order = DialogFocusOrder(listOf(true, false, true))

        assertEquals(2, order.next(0))
        assertEquals(0, order.next(2))
        assertEquals(2, order.previous(0))
        assertEquals(0, DialogFocusOrder(listOf(true, false)).next(0), "a single focusable stop keeps the focus")
    }

    @Test
    fun `the stops are the field or the dialog, then the buttons enabled for the entered text`() {
        val rename = DialogPresentation.of(DialogRequest.Rename(Path.of("/ws/robot.kstates").toAbsolutePath()))

        assertEquals(3, DialogFocusOrder.of(rename, "room.kstates").size)
        assertEquals(1, DialogFocusOrder.of(rename, "room.kstates").next(0), "field → Rename")
        assertEquals(2, DialogFocusOrder.of(rename, " ").next(0), "field → Cancel while Rename is disabled")
        assertFailsWith<IllegalArgumentException> { DialogFocusOrder(listOf(false, true)) }
    }
}
