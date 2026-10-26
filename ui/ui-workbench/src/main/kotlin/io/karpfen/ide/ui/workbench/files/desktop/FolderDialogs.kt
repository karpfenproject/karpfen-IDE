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
package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser

/** A modal native folder dialog; called on the AWT event dispatch thread, returns the chosen folder or null if cancelled. */
internal fun interface FolderDialog {
    /** Shows the dialog with [title], starting in [initialDirectory]; the chosen folder, or null if cancelled. */
    fun show(title: String, initialDirectory: Path?): Path?

    companion object {
        /** The platform's folder dialog: the system folder panel on macOS, a Swing `JFileChooser` elsewhere. */
        fun of(platform: HostPlatform): FolderDialog = when (platform) {
            HostPlatform.MAC_OS -> MacFolderDialog
            HostPlatform.WINDOWS, HostPlatform.LINUX -> SwingFolderDialog
        }
    }
}

/** The AWT `FileDialog` in directory mode (the macOS folder panel). */
private object MacFolderDialog : FolderDialog {
    /** Switches the macOS `FileDialog` to choosing directories. */
    private const val DIRECTORY_MODE = "apple.awt.fileDialogForDirectories"

    override fun show(title: String, initialDirectory: Path?): Path? {
        val previous = System.getProperty(DIRECTORY_MODE)
        System.setProperty(DIRECTORY_MODE, "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            initialDirectory?.let { dialog.directory = it.toString() }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return Path.of(directory, file).toAbsolutePath().normalize()
        } finally {
            if (previous == null) System.clearProperty(DIRECTORY_MODE) else System.setProperty(DIRECTORY_MODE, previous)
        }
    }
}

/** A Swing `JFileChooser` in directories-only mode (Windows, Linux). */
private object SwingFolderDialog : FolderDialog {
    override fun show(title: String, initialDirectory: Path?): Path? {
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
}
