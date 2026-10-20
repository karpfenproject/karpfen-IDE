package io.karpfen.ide.ui.editor.view.clipboard

import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * The system clipboard as a [ClipboardPort] (decisions 15 and 30 of the work-package plan): the AWT `Toolkit`
 * clipboard, resolved lazily on first use, so creating the port never touches the toolkit. When the JVM is headless
 * (tests, CI) — decided by [headless] at first use — it degrades to an [InMemoryClipboard] and never throws
 * `HeadlessException`. Failures of the platform clipboard (busy, no text flavor) are logged and read as "no text".
 * Used from the UI thread.
 */
internal class LazySystemClipboard(
    private val headless: () -> Boolean = GraphicsEnvironment::isHeadless,
    private val platform: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
) : ClipboardPort {

    private val delegate: ClipboardPort by lazy {
        if (headless()) InMemoryClipboard() else AwtClipboard(platform())
    }

    override fun getText(): String? = delegate.getText()

    override fun setText(text: String) {
        delegate.setText(text)
    }
}

/** A clipboard in memory (headless fallback). */
internal class InMemoryClipboard : ClipboardPort {
    private var content: String? = null

    override fun getText(): String? = content

    override fun setText(text: String) {
        content = text
    }
}

/** Text on an AWT [clipboard]. */
private class AwtClipboard(private val clipboard: Clipboard) : ClipboardPort {

    override fun getText(): String? = try {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
    } catch (e: Exception) {
        logger.warn("could not read the system clipboard", e)
        null
    }

    override fun setText(text: String) {
        try {
            val selection = StringSelection(text)
            clipboard.setContents(selection, selection)
        } catch (e: Exception) {
            logger.warn("could not write the system clipboard", e)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(AwtClipboard::class.java)
    }
}
