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

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.Path

/** The platform actions the file operations need. */
internal enum class DesktopAction { MOVE_TO_TRASH, REVEAL, OPEN }

/**
 * The platform side of the file operations. It is a seam, so tests can check the java.nio part with a fake. Its calls
 * fail the same way as those of `java.awt.Desktop`. Besides `IOException` they throw unchecked exceptions: an
 * `IllegalArgumentException` for a file that does not exist, a `SecurityException` or an
 * `UnsupportedOperationException`. [DesktopProjectFileOperations] translates them.
 */
internal interface DesktopActions {
    /** True if [action] is available on this platform. Never touches the toolkit in a headless JVM. */
    fun supports(action: DesktopAction): Boolean

    /** Moves [path] to the trash. Returns false if the platform refused. */
    fun moveToTrash(path: Path): Boolean

    /** Shows [path] in the file manager. */
    fun reveal(path: Path)

    /**
     * Opens [path] in the default application.
     *
     * @throws IOException if the platform fails to launch the application.
     * @throws IllegalArgumentException if [path] does not exist. `java.awt.Desktop` checks this.
     */
    fun open(path: Path)
}

/**
 * [DesktopActions] over `java.awt.Desktop`. It is safe in a headless JVM. The toolkit is asked only once, lazily, and
 * only when the JVM is not headless and `Desktop.isDesktopSupported()` returns true. Otherwise no action is supported.
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
