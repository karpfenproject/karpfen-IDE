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

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * What one start of the IDE was asked to do: open [initialFolder] (absolute, normalized; null for the empty state) and,
 * for launch smoke tests, exit with status 0 after [exitAfterMs] milliseconds. [warnings] name the inputs that were
 * ignored; `main` logs them once logging is configured.
 */
internal data class LaunchOptions(
    val initialFolder: Path?,
    val exitAfterMs: Long?,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        /** Environment variable of the self-exit delay in milliseconds (kept from phase 0). */
        const val EXIT_AFTER_MS_ENVIRONMENT_VARIABLE: String = "KARPFEN_IDE_EXIT_AFTER_MS"

        /**
         * The options of the command line [args] (`[folder]`) and the [environment]. A folder argument that is not an
         * existing directory, extra arguments and a delay that is not a non-negative number are ignored with a warning,
         * so a typo never keeps the IDE from starting.
         */
        fun parse(
            args: List<String>,
            environment: (String) -> String?,
            isDirectory: (Path) -> Boolean = Files::isDirectory,
        ): LaunchOptions {
            val warnings = mutableListOf<String>()
            if (args.size > 1) warnings += "Ignoring extra arguments ${args.drop(1)}: the IDE opens one folder"
            val folder = args.firstOrNull()?.let { folderOf(it, isDirectory, warnings) }
            val exitAfterMs = environment(EXIT_AFTER_MS_ENVIRONMENT_VARIABLE)?.let { exitDelayOf(it, warnings) }
            return LaunchOptions(folder, exitAfterMs, warnings)
        }

        /** The folder [argument] as an absolute, normalized directory, or null with a warning added to [warnings]. */
        private fun folderOf(argument: String, isDirectory: (Path) -> Boolean, warnings: MutableList<String>): Path? {
            val path = try {
                Path.of(argument).toAbsolutePath().normalize()
            } catch (e: InvalidPathException) {
                warnings += "Ignoring the folder argument '$argument': ${e.reason}"
                return null
            }
            if (isDirectory(path)) return path
            warnings += "Ignoring the folder argument '$argument': $path is not a directory"
            return null
        }

        /** The exit delay [value] in milliseconds, or null with a warning added to [warnings] if it is invalid. */
        private fun exitDelayOf(value: String, warnings: MutableList<String>): Long? =
            value.trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: null.also {
                    warnings += "Ignoring $EXIT_AFTER_MS_ENVIRONMENT_VARIABLE='$value': not a non-negative number of milliseconds"
                }
    }
}
