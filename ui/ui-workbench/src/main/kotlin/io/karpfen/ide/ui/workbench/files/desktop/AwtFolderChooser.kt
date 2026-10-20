package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.coroutines.resume

/**
 * The native [FolderChooser]: on macOS the AWT `FileDialog` in directory mode (the system's folder panel), elsewhere a
 * Swing `JFileChooser` in directories-only mode. The modal dialog runs on the AWT event dispatch thread; the caller
 * suspends until it closes. Headless-safe: returns null without touching the toolkit when the JVM is headless.
 */
internal class AwtFolderChooser(private val platform: HostPlatform = HostPlatform.current()) : FolderChooser {

    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? {
        if (GraphicsEnvironment.isHeadless()) return null
        return suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater {
                val chosen = if (platform == HostPlatform.MAC_OS) macFolder(title, initialDirectory) else swingFolder(title, initialDirectory)
                continuation.resume(chosen)
            }
        }
    }

    private fun macFolder(title: String, initialDirectory: Path?): Path? {
        val previous = System.getProperty(MAC_DIRECTORY_MODE)
        System.setProperty(MAC_DIRECTORY_MODE, "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            initialDirectory?.let { dialog.directory = it.toString() }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return Path.of(directory, file).toAbsolutePath().normalize()
        } finally {
            if (previous == null) System.clearProperty(MAC_DIRECTORY_MODE) else System.setProperty(MAC_DIRECTORY_MODE, previous)
        }
    }

    private fun swingFolder(title: String, initialDirectory: Path?): Path? {
        val chooser = JFileChooser(initialDirectory?.toFile())
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
        } else {
            null
        }
    }

    private companion object {
        /** Switches the macOS `FileDialog` to choosing directories. */
        const val MAC_DIRECTORY_MODE = "apple.awt.fileDialogForDirectories"
    }
}
