package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.FolderChooser
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Path

/**
 * The native [FolderChooser]: shows the modal [dialog] ([FolderDialog.of] the platform) on the AWT event dispatch thread
 * while the caller suspends. The caller is resumed in every case: with the answer, or with an `IOException` for
 * whatever the dialog threw (e.g. the JDK's shell-folder errors for network folders on Windows), so a failing dialog
 * can never leave the intent that asked waiting forever (it holds the workbench's intent turn, decision 42).
 * Headless-safe: returns null without touching the toolkit when [headless].
 */
internal class AwtFolderChooser(
    private val dialog: FolderDialog,
    private val headless: () -> Boolean = GraphicsEnvironment::isHeadless,
) : FolderChooser {

    /** The folder dialog of [platform]. */
    constructor(platform: HostPlatform = HostPlatform.current()) : this(FolderDialog.of(platform))

    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? {
        if (headless()) return null
        return suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater { continuation.resumeWith(show(title, initialDirectory)) }
        }
    }

    private fun show(title: String, initialDirectory: Path?): Result<Path?> = try {
        Result.success(dialog.show(title, initialDirectory))
    } catch (e: Throwable) {
        // Everything, Errors included: the JDK's shell-folder bugs surface as InternalError.
        Result.failure(IOException("The folder dialog failed: ${e.message ?: e}", e))
    }
}
