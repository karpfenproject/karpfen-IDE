package io.karpfen.ide.app.launch

import io.karpfen.ide.ui.foundation.keys.HostPlatform

/** The application's name in the macOS menu bar and the Dock. */
internal const val APPLICATION_NAME: String = "karpfen IDE"

/**
 * The system properties the desktop integration needs before AWT starts: on macOS the window menu bar goes into the
 * native screen menu bar (`apple.laf.useScreenMenuBar`) under the application's name, and Quit (Cmd+Q, the application
 * menu, logout) closes the windows (`apple.eawt.quitStrategy` = `CLOSE_ALL_WINDOWS`) instead of AWT's default
 * `System.exit(0)`, so it runs the main window's close request — the unsaved-changes flow and the settings — like the
 * close button, and Cancel keeps the IDE running; other platforms need none.
 */
internal fun desktopProperties(platform: HostPlatform): Map<String, String> = when (platform) {
    HostPlatform.MAC_OS -> mapOf(
        "apple.laf.useScreenMenuBar" to "true",
        "apple.awt.application.name" to APPLICATION_NAME,
        "apple.eawt.quitStrategy" to "CLOSE_ALL_WINDOWS",
    )
    HostPlatform.WINDOWS, HostPlatform.LINUX -> emptyMap()
}

/**
 * Sets every property of [desktopProperties] for [platform] that is not set yet ([getProperty] null), so an explicit
 * `-D` on the command line wins. `main` calls it before anything touches AWT.
 */
internal fun applyDesktopProperties(
    platform: HostPlatform,
    getProperty: (String) -> String?,
    setProperty: (String, String) -> Unit,
) {
    desktopProperties(platform).forEach { (key, value) -> if (getProperty(key) == null) setProperty(key, value) }
}
