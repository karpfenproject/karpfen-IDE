/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.editor.view.clipboard

import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorActionContext
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.action.TestEditor
import io.karpfen.ide.ui.editor.view.systemClipboard
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** A platform clipboard that another application holds (AWT throws `IllegalStateException` then). */
    private class BusyClipboard : Clipboard("busy") {
        override fun setContents(contents: Transferable?, owner: ClipboardOwner?) {
            throw IllegalStateException("cannot open system clipboard")
        }
    }

    @Test
    fun `cut keeps the text when the system clipboard cannot be written`() {
        val editor = TestEditor("keep ^important|")
        val context = object : EditorActionContext by editor {
            override val clipboard: ClipboardPort = LazySystemClipboard(headless = { false }, platform = { BusyClipboard() })
        }

        val done = editor.registry.execute(EditorActionIds.CUT, context)

        assertFalse(done, "nothing was cut")
        assertEquals("keep ^important|", editor.marked(), "the text exists only where it was")
        assertEquals(0L, editor.text.snapshot.value.version)
    }

    @Test
    fun `a whole line is marked on the platform clipboard until something else is copied`() {
        val awt = Clipboard("test")
        val port = LazySystemClipboard(headless = { false }, platform = { awt })

        assertTrue(port.setText("line\n", wholeLine = true))
        assertTrue(port.holdsWholeLine())
        assertEquals("line\n", port.getText())
        assertEquals("line\n", awt.getData(DataFlavor.stringFlavor), "other applications see plain text")

        assertTrue(port.setText("part"))
        assertFalse(port.holdsWholeLine(), "a text copied from a selection")
        port.setText("line\n", wholeLine = true)
        awt.setContents(java.awt.datatransfer.StringSelection("line\n"), null)
        assertFalse(port.holdsWholeLine(), "the same text copied by another application")
    }

    @Test
    fun `the in-memory clipboard keeps the whole-line mark and a failed write reports false`() {
        val memory = LazySystemClipboard(headless = { true })
        memory.setText("a\n", wholeLine = true)
        assertTrue(memory.holdsWholeLine())
        memory.setText("a")
        assertFalse(memory.holdsWholeLine())

        val busy = LazySystemClipboard(headless = { false }, platform = { BusyClipboard() })
        assertFalse(busy.setText("x"))
        assertFalse(busy.holdsWholeLine())
    }
}
