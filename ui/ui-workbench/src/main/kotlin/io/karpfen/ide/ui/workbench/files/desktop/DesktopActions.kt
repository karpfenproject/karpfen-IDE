package io.karpfen.ide.ui.workbench.files.desktop

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Path

/** The platform actions the file operations need. */
internal enum class DesktopAction { MOVE_TO_TRASH, REVEAL, OPEN }

/** The platform side of the file operations (a seam, so the java.nio part is testable with a fake). */
internal interface DesktopActions {
    /** True if [action] is available on this platform (never touches the toolkit when headless). */
    fun supports(action: DesktopAction): Boolean

    /** Moves [path] to the trash; false if the platform refused. */
    fun moveToTrash(path: Path): Boolean

    /** Shows [path] in the file manager. */
    fun reveal(path: Path)

    /** Opens [path] in the default application. @throws IOException if the platform fails. */
    fun open(path: Path)
}

/**
 * [DesktopActions] over `java.awt.Desktop`. Headless-safe: the toolkit is only asked (once, lazily) when the JVM is not
 * headless and `Desktop.isDesktopSupported()`; otherwise nothing is supported.
 */
internal class AwtDesktopActions : DesktopActions {

    private val desktop: Desktop? by lazy {
        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    }

    override fun supports(action: DesktopAction): Boolean = desktop?.isSupported(action.awtAction) == true

    override fun moveToTrash(path: Path): Boolean = desktop?.moveToTrash(path.toFile()) == true

    override fun reveal(path: Path) {
        desktop?.browseFileDirectory(path.toFile())
    }

    override fun open(path: Path) {
        desktop?.open(path.toFile())
    }

    private val DesktopAction.awtAction: Desktop.Action
        get() = when (this) {
            DesktopAction.MOVE_TO_TRASH -> Desktop.Action.MOVE_TO_TRASH
            DesktopAction.REVEAL -> Desktop.Action.BROWSE_FILE_DIR
            DesktopAction.OPEN -> Desktop.Action.OPEN
        }
}
