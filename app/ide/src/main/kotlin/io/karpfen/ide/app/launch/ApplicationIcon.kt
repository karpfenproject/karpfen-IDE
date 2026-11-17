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

import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The icon of the application. It is shown in two places and in two cuts, because the platforms differ in where they
 * take the icon from and in how it should look.
 *
 * Windows and Linux show the icon of each window in its title bar and in the taskbar. So [showOnEveryWindow] gives
 * every window of the IDE the plain square image [WINDOW], scaled once into [WINDOW_SIZES] as [windowImages]. The
 * windows get these images as plain AWT images, so the platform only picks the size that fits and needs no further
 * conversion. macOS shows the icon of the application in the Dock. An installed application brings that icon in its
 * bundle. An IDE started from Gradle or from a plain `java` command has no bundle, and the Dock would show the Java cup.
 * So [showInTaskbar] hands the icon to the Dock when the IDE starts.
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

    /** The path of the plain square icon among the resources, for the windows. */
    const val WINDOW: String = "/icons/karpfen-ide.png"

    /** The path of the icon in the shape of a Mac application icon among the resources, for the Dock. */
    const val DOCK: String = "/icons/karpfen-ide-macos.png"

    /**
     * The sides in pixels of the window icons in [windowImages]. Windows draws the small icon of the title bar at 16
     * pixels and the large one of the taskbar and the task switcher at 32 pixels, each times the display scale of 100,
     * 125, 150, 200 or 250 percent, and Linux desktops ask for 16 to 256 pixels. With a fitting size at hand, AWT
     * copies the image instead of scaling it with its plain bilinear filter.
     */
    val WINDOW_SIZES: List<Int> = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

    /**
     * The window icon [WINDOW] in each of the [WINDOW_SIZES], in that order, or an empty list if the resource cannot
     * be loaded. The images are scaled on first use and then kept, because every window that opens receives them.
     */
    val windowImages: List<BufferedImage> by lazy {
        load(WINDOW)?.let { source -> WINDOW_SIZES.map { scaled(source, it) } }.orEmpty()
    }

    /** The image at the resource [path], or null if the resource is missing or cannot be read. */
    fun load(path: String): BufferedImage? =
        ApplicationIcon::class.java.getResourceAsStream(path)?.use { runCatching { ImageIO.read(it) }.getOrNull() }

    /**
     * Makes every window of the IDE carry [windowImages] from the moment it becomes displayable, through a
     * [WindowIcons] listener on [toolkit]. Call it once before the first window opens. Further calls change nothing.
     * Returns whether this call installed the listener.
     */
    fun showOnEveryWindow(toolkit: Toolkit = Toolkit.getDefaultToolkit()): Boolean =
        WindowIcons(windowImages).install(toolkit)

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

    /**
     * The square [source] scaled to [size] pixels. It halves the image step by step while it is at least twice as
     * large as [size] and draws the last step to the exact size, each step with bicubic interpolation. A single
     * bicubic step from a large image to a small one skips most source pixels and looks ragged. Halving lets every
     * source pixel count, as a box filter would.
     */
    internal fun scaled(source: BufferedImage, size: Int): BufferedImage {
        var image = source
        while (image.width / 2 >= size) image = drawn(image, image.width / 2)
        return if (image !== source && image.width == size) image else drawn(image, size)
    }

    /** A new translucent image of [size] by [size] pixels with [image] drawn into it at high quality. */
    private fun drawn(image: BufferedImage, size: Int): BufferedImage {
        val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(
                RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY,
            )
            graphics.drawImage(image, 0, 0, size, size, null)
        } finally {
            graphics.dispose()
        }
        return target
    }
}
