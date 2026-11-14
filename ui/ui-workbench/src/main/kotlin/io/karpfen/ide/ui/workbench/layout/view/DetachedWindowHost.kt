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
package io.karpfen.ide.ui.workbench.layout.view

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.workbench.settings.WindowBounds

/**
 * Opens the operating system window that holds a detached tool window. The `Workbench` composable calls [Window] while
 * a tool window is detached and stops calling it when the tool window docks again or is hidden, which closes the
 * window.
 *
 * The workbench does not open windows itself. A window needs a desktop, and the headless tests of the workbench run
 * without one. The application's composition root therefore supplies the host that opens a real window, and tests
 * supply one that draws the content in place. The workbench decides what the window shows, and the host decides only
 * how the window is made.
 */
public interface DetachedWindowHost {

    /**
     * Shows [content] in a window described by [window] for as long as this composable stays in the composition. The
     * content brings its own theme and zoom, so the host adds nothing around it.
     */
    @Composable
    public fun Window(window: DetachedWindow, content: @Composable () -> Unit)

    public companion object {
        /**
         * A host without windows. A detached tool window then shows nowhere until it is docked again. It serves where
         * no desktop exists.
         */
        public val None: DetachedWindowHost = object : DetachedWindowHost {
            @Composable
            override fun Window(window: DetachedWindow, content: @Composable () -> Unit) = Unit
        }
    }
}

/**
 * What a [DetachedWindowHost] needs to know about the window of a detached tool window.
 *
 * @property title the window's title.
 * @property initialBounds the bounds the window opens with, or null for the host's default placement. The host reads
 *   them only when the window opens.
 * @property raiseRequests a count that changes whenever the window should be brought to the front, see
 *   `LayoutState.raiseRequests`.
 * @property onBoundsChange receives the window's bounds whenever the user moves or resizes it, so they can be restored.
 * @property onCloseRequest called when the user closes the window with its own close button. The workbench docks the
 *   tool window again, and the host closes the window as soon as the composition stops calling it.
 */
public class DetachedWindow(
    public val title: String,
    public val initialBounds: WindowBounds?,
    public val raiseRequests: Int,
    public val onBoundsChange: (WindowBounds) -> Unit,
    public val onCloseRequest: () -> Unit,
)
