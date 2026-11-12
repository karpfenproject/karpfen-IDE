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

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The icon of the application, a square PNG among the resources. It is shown in two places, because the platforms
 * differ in where they take it from.
 *
 * Windows and Linux show the icon of the window in the taskbar, so the main window gets the icon as [painter]. macOS
 * shows the icon of the application in the Dock. An installed application brings that icon in its bundle. An IDE
 * started from Gradle or from a plain `java` command has no bundle, and the Dock would show the Java cup. So
 * [showInTaskbar] hands the icon to the Dock when the IDE starts.
 *
 * The installers take their icons from the packaging files of this module, which are made from the same image.
 */
internal object ApplicationIcon {

    /** The path of the icon among the resources. */
    const val RESOURCE: String = "/icons/karpfen-ide.png"

    /** The icon as an image, or null if the resource is missing or cannot be read. */
    fun load(): BufferedImage? =
        ApplicationIcon::class.java.getResourceAsStream(RESOURCE)?.use { runCatching { ImageIO.read(it) }.getOrNull() }

    /** The icon for the window, or null if it cannot be loaded. */
    fun painter(): Painter? = load()?.let { BitmapPainter(it.toComposeImageBitmap()) }

    /**
     * Makes the desktop show [image] for the running application where the desktop supports that. macOS does, in the
     * Dock. A desktop that does not is left alone, and so is a headless JVM, as in the tests. A desktop that refuses
     * is ignored too, because the IDE works without its icon.
     */
    fun showInTaskbar(image: Image? = load()) {
        if (image == null || GraphicsEnvironment.isHeadless() || !Taskbar.isTaskbarSupported()) return
        runCatching {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = image
        }
    }
}
