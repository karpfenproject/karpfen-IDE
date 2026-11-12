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

import io.karpfen.ide.ui.foundation.keys.HostPlatform

/**
 * The application's name in the macOS menu bar. The Dock shows the same name, but it only takes it from the `-Xdock`
 * option on the command line of the JVM, which the build script of this module sets for `run` and for the packages.
 */
internal const val APPLICATION_NAME: String = "Karpfen IDE"

/**
 * The system properties the desktop integration needs before AWT starts. Only macOS needs any.
 *
 * On macOS, `apple.laf.useScreenMenuBar` puts the window menu bar into the native screen menu bar, under the
 * application's name. `apple.eawt.quitStrategy` set to `CLOSE_ALL_WINDOWS` makes Quit close the windows instead of
 * calling AWT's default `System.exit(0)`. This covers Cmd+Q, the Quit item of the application menu and logout. So Quit
 * runs the main window's close request just like the close button, with the unsaved-changes flow and the saving of
 * the settings, and Cancel keeps the IDE running.
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
 * Sets every property of [desktopProperties] for [platform] that is not set yet, which means [getProperty] returns
 * null for it. So an explicit `-D` on the command line wins. `main` calls it before anything touches AWT.
 */
internal fun applyDesktopProperties(
    platform: HostPlatform,
    getProperty: (String) -> String?,
    setProperty: (String, String) -> Unit,
) {
    desktopProperties(platform).forEach { (key, value) -> if (getProperty(key) == null) setProperty(key, value) }
}
