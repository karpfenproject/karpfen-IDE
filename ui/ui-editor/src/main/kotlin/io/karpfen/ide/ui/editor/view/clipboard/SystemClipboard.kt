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
 * The system clipboard as a [ClipboardPort] (decisions 15 and 30 of the work-package plan): the AWT `Toolkit`
 * clipboard, resolved lazily on first use, so creating the port never touches the toolkit. When the JVM is headless
 * (tests, CI) — decided by [headless] at first use — it degrades to an [InMemoryClipboard] and never throws
 * `HeadlessException`. Failures of the platform clipboard (busy, no text flavor) are logged; a failed read is "no text",
 * a failed write is reported as `false`. Used from the UI thread.
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

/** A clipboard in memory (headless fallback). */
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
 * Text on an AWT [clipboard]. A whole line is put there with a JVM-local flavor next to the string flavor
 * ([WholeLineSelection]): other applications see plain text, and the mark disappears as soon as anything else owns the
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

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AwtClipboard::class.java)
    }
}

/** A whole line of [text] for the AWT clipboard: the string flavor plus the JVM-local mark [FLAVOR]. */
private class WholeLineSelection(private val text: String) : Transferable, ClipboardOwner {

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR, DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == FLAVOR || flavor == DataFlavor.stringFlavor

    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        FLAVOR -> true
        DataFlavor.stringFlavor -> text
        else -> throw UnsupportedFlavorException(flavor)
    }

    override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) = Unit

    companion object {
        /** Marks a whole line copied or cut by an editor of this IDE (never exported to other applications). */
        val FLAVOR: DataFlavor = DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=java.lang.Boolean", "karpfen whole line")
    }
}
