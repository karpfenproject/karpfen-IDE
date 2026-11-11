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

import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The outcome of [applyHomeOverride].
 *
 * @property home the IDE home that was set, or null if none was set.
 * @property warning why the environment variable was ignored, or null. `main` logs it once logging is configured.
 */
internal data class HomeOverride(val home: Path?, val warning: String? = null)

/**
 * Copies the environment variable `SettingsLocations.HOME_ENVIRONMENT_VARIABLE`, which is `KARPFEN_IDE_HOME`, into
 * the system property `SettingsLocations.HOME_PROPERTY`, which is `karpfen.ide.home`, through [setProperty]. The value
 * is copied only if it is set and not blank, and it is stored as an absolute normalized path. A value that is no valid
 * path is ignored with a warning.
 *
 * `main` calls this before the first logger exists, because two readers depend on the property.
 * `SettingsLocations.defaultDirectory()` finds the settings with it, and the app's `logback.xml` finds the log file
 * with it. So a launch smoke test with a temporary home never touches the user's own settings or log. The function is
 * pure apart from [setProperty], and it logs nothing.
 */
internal fun applyHomeOverride(
    environment: (String) -> String?,
    setProperty: (String, String) -> Unit,
): HomeOverride {
    val value = environment(SettingsLocations.HOME_ENVIRONMENT_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HomeOverride(home = null)
    val home = try {
        Path.of(value).toAbsolutePath().normalize()
    } catch (e: InvalidPathException) {
        return HomeOverride(
            home = null,
            warning = "Ignoring ${SettingsLocations.HOME_ENVIRONMENT_VARIABLE}='$value': ${e.reason}",
        )
    }
    setProperty(SettingsLocations.HOME_PROPERTY, home.toString())
    return HomeOverride(home)
}
