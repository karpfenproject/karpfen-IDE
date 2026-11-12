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
 * The icon of the application. It is shown in two places and in two cuts, because the platforms differ in where they
 * take the icon from and in how it should look.
 *
 * Windows and Linux show the icon of the window in the taskbar, so the main window gets the plain square image
 * [WINDOW] as [painter]. macOS shows the icon of the application in the Dock. An installed application brings that
 * icon in its bundle. An IDE started from Gradle or from a plain `java` command has no bundle, and the Dock would show
 * the Java cup. So [showInTaskbar] hands the icon to the Dock when the IDE starts.
 *
 * The Dock draws the image as it is and adds no shape of its own. So [DOCK] is the image cut to the shape of a Mac
 * application icon. On a canvas of 1024 pixels the artwork fills a rounded square of 824 pixels in the middle, with
 * corners of a radius of 185.4 pixels and a soft shadow below. The 100 pixels around it stay empty, which makes the
 * icon as large as its neighbours in the Dock.
 *
 * The installers take their icons from the packaging files of this module, which are made from the same two images:
 * the icns file from [DOCK] and the ico file from [WINDOW].
 */
internal object ApplicationIcon {

    /** The path of the plain square icon among the resources, for the window. */
    const val WINDOW: String = "/icons/karpfen-ide.png"

    /** The path of the icon in the shape of a Mac application icon among the resources, for the Dock. */
    const val DOCK: String = "/icons/karpfen-ide-macos.png"

    /** The image at the resource [path], or null if the resource is missing or cannot be read. */
    fun load(path: String): BufferedImage? =
        ApplicationIcon::class.java.getResourceAsStream(path)?.use { runCatching { ImageIO.read(it) }.getOrNull() }

    /** The icon for the window, or null if it cannot be loaded. */
    fun painter(): Painter? = load(WINDOW)?.let { BitmapPainter(it.toComposeImageBitmap()) }

    /**
     * Makes the desktop show [image] for the running application where the desktop supports that. macOS does, in the
     * Dock. A desktop that does not is left alone, and so is a headless JVM, as in the tests. A desktop that refuses
     * is ignored too, because the IDE works without its icon.
     */
    fun showInTaskbar(image: Image? = load(DOCK)) {
        if (image == null || GraphicsEnvironment.isHeadless() || !Taskbar.isTaskbarSupported()) return
        runCatching {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = image
        }
    }
}
