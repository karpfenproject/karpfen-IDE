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

import io.karpfen.ide.app.launch.ApplicationIcon
import io.karpfen.ide.app.launch.LaunchOptions
import io.karpfen.ide.app.launch.applyDesktopProperties
import io.karpfen.ide.app.launch.applyHomeOverride
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Entry point of the karpfen IDE desktop application. `karpfen-ide [folder]` opens the folder. Without a folder the
 * IDE shows its empty state.
 *
 * The environment variable `KARPFEN_IDE_HOME` moves the settings and the log. It is copied into the system property
 * `SettingsLocations.HOME_PROPERTY` before the first logger exists, which is why this file declares no top-level
 * logger. `KARPFEN_IDE_EXIT_AFTER_MS` makes the IDE exit with status 0 after that many milliseconds, which the launch
 * smoke tests use. The process exits with status 1 if the IDE could not start.
 */
public fun main(args: Array<String>) {
    exitProcess(launch(args))
}

/**
 * Starts the IDE with the command-line [args] as [main] describes and returns the exit status, 0 or 1. The IDE is the
 * [application] that the factory makes for the self-exit delay of the launch options. Launches for checks by hand pass
 * a factory with other collaborators.
 */
internal fun launch(args: Array<String>, application: (exitAfterMs: Long?) -> IdeApplication = { IdeApplication(it) }): Int {
    val home = applyHomeOverride(System::getenv, System::setProperty)
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
