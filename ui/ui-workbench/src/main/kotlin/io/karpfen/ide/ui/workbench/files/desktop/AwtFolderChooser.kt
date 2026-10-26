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
import io.karpfen.ide.ui.workbench.files.FolderChooser
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Path

/**
 * The native [FolderChooser]: shows the modal [dialog] ([FolderDialog.of] the platform) on the AWT event dispatch
 * thread while the caller suspends. The caller is resumed in every case: with the answer, or with an `IOException` for
 * whatever the dialog threw (e.g. the JDK's shell-folder errors for network folders on Windows), so a failing dialog
 * can never leave the intent that asked waiting forever. That intent holds the workbench's intent turn (the workbench
 * runs its intents one at a time), so a caller left suspended would also keep Save and Exit waiting forever.
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

    /**
     * Shows the dialog and captures everything it throws as an `IOException`, `Error`s included, because the JDK's
     * shell-folder bugs surface as `InternalError`.
     */
    private fun show(title: String, initialDirectory: Path?): Result<Path?> = try {
        Result.success(dialog.show(title, initialDirectory))
    } catch (e: Throwable) {
        Result.failure(IOException("The folder dialog failed: ${e.message ?: e}", e))
    }
}
