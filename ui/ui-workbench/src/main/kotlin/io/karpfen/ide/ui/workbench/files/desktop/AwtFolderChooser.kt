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
 * The native [FolderChooser]. It shows the modal [dialog], which [FolderDialog.of] picks for the platform, on the AWT
 * event dispatch thread while the caller suspends.
 *
 * The caller is resumed in every case. It gets either the answer or an `IOException` that wraps whatever the dialog
 * threw. The JDK's shell-folder errors for network folders on Windows are one example of such a failure. A failing
 * dialog can therefore never leave the intent that asked waiting forever. This matters because the workbench runs its
 * intents one at a time, and the asking intent holds that turn. A caller left suspended would also keep Save and Exit
 * waiting forever.
 *
 * The chooser is safe in a headless JVM. When [headless] returns true, it returns null without touching the toolkit.
 */
internal class AwtFolderChooser(
    private val dialog: FolderDialog,
    private val headless: () -> Boolean = GraphicsEnvironment::isHeadless,
) : FolderChooser {

    /** Creates the chooser with the folder dialog of [platform]. */
    constructor(platform: HostPlatform = HostPlatform.current()) : this(FolderDialog.of(platform))

    override suspend fun chooseFolder(title: String, initialDirectory: Path?): Path? {
        if (headless()) return null
        return suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater { continuation.resumeWith(show(title, initialDirectory)) }
        }
    }

    /**
     * Shows the dialog and wraps everything it throws in an `IOException`. That includes subclasses of `Error`, because
     * the JDK's shell-folder bugs surface as `InternalError`.
     */
    private fun show(title: String, initialDirectory: Path?): Result<Path?> = try {
        Result.success(dialog.show(title, initialDirectory))
    } catch (e: Throwable) {
        Result.failure(IOException("The folder dialog failed: ${e.message ?: e}", e))
    }
}
