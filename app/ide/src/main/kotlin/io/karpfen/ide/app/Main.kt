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
package io.karpfen.ide.app

import io.karpfen.ide.app.headless.HeadlessCommand
import io.karpfen.ide.app.headless.HeadlessLaunch
import io.karpfen.ide.app.launch.ApplicationIcon
import io.karpfen.ide.app.launch.LaunchOptions
import io.karpfen.ide.app.launch.applyDesktopProperties
import io.karpfen.ide.app.launch.applyHomeOverride
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Entry point of the karpfen IDE desktop application. `karpfen-ide [folder]` opens the folder in the window. Without a
 * folder the IDE shows its empty state.
 *
 * `karpfen-ide validate <folder>` and `karpfen-ide run <folder> …` run the IDE headless instead, without a window and
 * without touching AWT or Compose, as `HeadlessCommand` describes. They exit with the status of the command, as
 * `HeadlessExitCodes` lists them.
 *
 * The environment variable `KARPFEN_IDE_HOME` moves the settings and the log. It is copied into the system property
 * `SettingsLocations.HOME_PROPERTY` before the first logger exists, which is why this file declares no top-level
 * logger. `KARPFEN_IDE_EXIT_AFTER_MS` makes the window exit with status 0 after that many milliseconds, which the
 * launch smoke tests use. The process exits with status 1 if the window could not start.
 */
public fun main(args: Array<String>) {
    exitProcess(launch(args))
}

/**
 * Starts the IDE with the command-line [args] as [main] describes and returns the exit status. A headless command goes
 * to [headless], which carries it out. Otherwise the window starts, and the status is 0 or 1. The window is the
 * [application] that the factory makes for the self-exit delay of the launch options. Launches for checks by hand pass
 * a factory with other collaborators.
 */
internal fun launch(
    args: Array<String>,
    application: (exitAfterMs: Long?) -> IdeApplication = { IdeApplication(it) },
    headless: (HeadlessCommand) -> Int = { HeadlessLaunch().run(it) },
): Int {
    val home = applyHomeOverride(System::getenv, System::setProperty)
    HeadlessCommand.parse(args.asList())?.let { command -> return launchHeadless(command, home.warning, headless) }
    applyDesktopProperties(HostPlatform.current(), System::getProperty, System::setProperty)
    ApplicationIcon.showInTaskbar()
    val launch = LaunchOptions.parse(args.asList(), System::getenv)

    val log = LoggerFactory.getLogger("io.karpfen.ide.app.Main")
    (listOfNotNull(home.warning) + launch.warnings).forEach(log::warn)
    log.info("Starting karpfen IDE; settings and log in {}", SettingsLocations.defaultDirectory())

    return try {
        application(launch.exitAfterMs).run(launch.initialFolder)
        0
    } catch (e: Exception) {
        log.error("karpfen IDE stopped after a failure", e)
        1
    }
}

/**
 * Carries out the headless [command] through [headless] and returns its status. AWT is switched to headless first, so
 * nothing can reach for a display, and neither the desktop properties nor the taskbar icon are set. The [homeWarning]
 * of the home directory, if there is one, is logged.
 */
private fun launchHeadless(command: HeadlessCommand, homeWarning: String?, headless: (HeadlessCommand) -> Int): Int {
    System.setProperty(HeadlessLaunch.AWT_HEADLESS_PROPERTY, "true")
    val log = LoggerFactory.getLogger("io.karpfen.ide.app.Main")
    homeWarning?.let(log::warn)
    log.info("Starting karpfen IDE headless; log in {}", SettingsLocations.logDirectory())
    return headless(command)
}
