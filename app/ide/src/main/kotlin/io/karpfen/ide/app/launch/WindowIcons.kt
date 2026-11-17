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
package io.karpfen.ide.app.launch

import java.awt.AWTEvent
import java.awt.Image
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.AWTEventListenerProxy
import java.awt.event.HierarchyEvent

/**
 * Gives every window of the process the icon [images], the main window as well as the detached tool window, the folder
 * chooser and any window added later. Windows and Linux show the icon in the title bar and in the taskbar, and they
 * pick from [images] the one that fits the size they draw, so [images] holds the icon in several sizes.
 *
 * The icon is set the moment a window becomes displayable. AWT reports that with a [HierarchyEvent] whose flags hold
 * [HierarchyEvent.DISPLAYABILITY_CHANGED], dispatched inside `addNotify` right after the native window exists and
 * before it is shown. So the window never appears with the Java cup, and no code that opens a window has to remember
 * the icon. A dialog without an owner, such as the Swing file chooser opened without a parent, gets it the same way.
 *
 * The listener sees every window, also those that Compose or Swing create on their own. That is the point of it:
 * one place decides the icon of all windows, so two windows cannot differ. Whatever icon a window was given before it
 * became displayable is replaced.
 */
internal class WindowIcons(private val images: List<Image>) : AWTEventListener {

    /**
     * Registers this listener with [toolkit] for hierarchy events, unless a [WindowIcons] listener is registered there
     * already, so that starting the application twice in one JVM, as the launch tests do, keeps one listener. Returns
     * whether this call registered it. Without [images] nothing is registered, because an empty list would only clear
     * the icons the windows have.
     */
    fun install(toolkit: Toolkit): Boolean {
        if (images.isEmpty() || isInstalled(toolkit)) return false
        toolkit.addAWTEventListener(this, AWTEvent.HIERARCHY_EVENT_MASK)
        return true
    }

    /** Gives the window behind [event] the icon if the event says that this window has just become displayable. */
    override fun eventDispatched(event: AWTEvent) {
        val window = (event as? HierarchyEvent)?.changed as? Window ?: return
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() == 0L || !window.isDisplayable) return
        window.iconImages = images
    }

    companion object {
        /**
         * Whether a [WindowIcons] listener is registered with [toolkit]. The toolkit hands out its listeners wrapped in
         * an [AWTEventListenerProxy], so the check looks inside the proxy.
         */
        fun isInstalled(toolkit: Toolkit): Boolean =
            toolkit.getAWTEventListeners(AWTEvent.HIERARCHY_EVENT_MASK).any {
                (it as? AWTEventListenerProxy)?.listener is WindowIcons || it is WindowIcons
            }
    }
}
