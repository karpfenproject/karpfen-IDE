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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * The system clipboard as a [ClipboardPort]. It uses the clipboard of the AWT `Toolkit`, resolved on first use, so
 * creating the port never touches the toolkit. When the JVM is headless, as in tests and CI, it falls back to an
 * [InMemoryClipboard] and never throws `HeadlessException`. [headless] decides this at first use.
 *
 * Failures of the platform clipboard are logged. Examples are a busy clipboard or one without a text flavor. A failed
 * read counts as "no text", and a failed write is reported as `false`. Use the class from the UI thread.
 */
internal class LazySystemClipboard(
    private val headless: () -> Boolean = GraphicsEnvironment::isHeadless,
    private val platform: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
) : ClipboardPort {

    private val delegate: ClipboardPort by lazy {
        if (headless()) InMemoryClipboard() else AwtClipboard(platform())
    }

    override fun getText(): String? = delegate.getText()

    override fun setText(text: String, wholeLine: Boolean): Boolean = delegate.setText(text, wholeLine)

    override fun holdsWholeLine(): Boolean = delegate.holdsWholeLine()
}

/** A clipboard in memory. It is the fallback when the JVM is headless. */
internal class InMemoryClipboard : ClipboardPort {
    private var content: String? = null
    private var wholeLine = false

    override fun getText(): String? = content

    override fun setText(text: String, wholeLine: Boolean): Boolean {
        content = text
        this.wholeLine = wholeLine
        return true
    }

    override fun holdsWholeLine(): Boolean = wholeLine
}

/**
 * Text on an AWT [clipboard]. A whole line is put there with a JVM-local flavor next to the string flavor, through
 * [WholeLineSelection]. Other applications see plain text, and the mark disappears as soon as anything else owns the
 * clipboard.
 */
private class AwtClipboard(private val clipboard: Clipboard) : ClipboardPort {

    override fun getText(): String? = try {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
    } catch (e: Exception) {
        logger.warn("could not read the system clipboard", e)
        null
    }

    override fun setText(text: String, wholeLine: Boolean): Boolean = try {
        if (wholeLine) {
            WholeLineSelection(text).let { clipboard.setContents(it, it) }
        } else {
            StringSelection(text).let { clipboard.setContents(it, it) }
        }
        true
    } catch (e: Exception) {
        logger.warn("could not write the system clipboard", e)
        false
    }

    override fun holdsWholeLine(): Boolean = try {
        clipboard.isDataFlavorAvailable(WholeLineSelection.FLAVOR)
    } catch (e: Exception) {
        logger.warn("could not read the system clipboard", e)
        false
    }

    /** The logger. */
    private companion object {
        /** Reports failures of the platform clipboard. */
        val logger: Logger = LoggerFactory.getLogger(AwtClipboard::class.java)
    }
}

/** A whole line of [text] for the AWT clipboard. It offers the string flavor plus the JVM-local mark [FLAVOR]. */
private class WholeLineSelection(private val text: String) : Transferable, ClipboardOwner {

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR, DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == FLAVOR || flavor == DataFlavor.stringFlavor

    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        FLAVOR -> true
        DataFlavor.stringFlavor -> text
        else -> throw UnsupportedFlavorException(flavor)
    }

    override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) = Unit

    /** Holds the mark. */
    companion object {
        /** Marks a whole line that an editor of this IDE copied or cut. It is never exported to other applications. */
        val FLAVOR: DataFlavor = DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=java.lang.Boolean", "karpfen whole line")
    }
}
