package io.karpfen.ide.ui.editor.view.clipboard

import io.karpfen.ide.ui.editor.view.systemClipboard
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The lazy system clipboard: no toolkit access on creation, in-memory when headless, AWT otherwise. */
class SystemClipboardTest {

    @Test
    fun `creating the port touches nothing, and headless it keeps text in memory`() {
        var resolved = 0
        val port = LazySystemClipboard(headless = { true }, platform = { resolved++; error("no toolkit when headless") })

        assertNull(port.getText())
        port.setText("copied")
        assertEquals("copied", port.getText())
        assertEquals(0, resolved)
    }

    @Test
    fun `the default port works in the headless test JVM`() {
        val port = systemClipboard()

        port.setText("x")
        assertEquals("x", port.getText())
    }

    @Test
    fun `with a display the platform clipboard is used, resolved once on first use`() {
        var resolved = 0
        val awt = Clipboard("test")
        val port = LazySystemClipboard(headless = { false }, platform = { resolved++; awt })
        assertEquals(0, resolved)

        port.setText("shared")
        assertEquals("shared", awt.getData(DataFlavor.stringFlavor))
        awt.setContents(java.awt.datatransfer.StringSelection("from elsewhere"), null)
        assertEquals("from elsewhere", port.getText())
        assertEquals(1, resolved)
    }

    @Test
    fun `clipboard contents without text and failures read as no text`() {
        val awt = Clipboard("test")
        val port = LazySystemClipboard(headless = { false }, platform = { awt })
        assertNull(port.getText(), "empty")

        awt.setContents(object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = true

            override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
        }, ClipboardOwner { _, _ -> })

        assertNull(port.getText())
    }
}
