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

import io.karpfen.ide.app.launch.LaunchOptions
import io.karpfen.ide.app.launch.applyDesktopProperties
import io.karpfen.ide.app.launch.applyHomeOverride
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Entry point of the karpfen IDE desktop application: `karpfen-ide [folder]` opens the folder, or the empty state
 * without one. `KARPFEN_IDE_HOME` moves settings and log (copied into `SettingsLocations.HOME_PROPERTY` before the
 * first logger exists, so this file declares no top-level logger); `KARPFEN_IDE_EXIT_AFTER_MS` makes the IDE exit with
 * status 0 after that time (launch smoke tests). Exits with status 1 if the IDE could not start.
 */
public fun main(args: Array<String>) {
    val home = applyHomeOverride(System::getenv, System::setProperty)
    applyDesktopProperties(HostPlatform.current(), System::getProperty, System::setProperty)
    val launch = LaunchOptions.parse(args.asList(), System::getenv)

    val log = LoggerFactory.getLogger("io.karpfen.ide.app.Main")
    (listOfNotNull(home.warning) + launch.warnings).forEach(log::warn)
    log.info("Starting karpfen IDE; settings and log in {}", SettingsLocations.defaultDirectory())

    val status = try {
        IdeApplication(launch.exitAfterMs).run(launch.initialFolder)
        0
    } catch (e: Exception) {
        log.error("karpfen IDE stopped after a failure", e)
        1
    }
    exitProcess(status)
}
