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
package io.karpfen.ide.ui.workbench.settings

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.format.DateTimeFormatter

/**
 * The settings file `settings.json` in [directory] on disk: reading, atomic writing (a temporary file in the same
 * directory, then an atomic move) and moving a corrupt file aside under a name taken from [clock]
 * (`settings.json.corrupt-<yyyyMMdd-HHmmss>`, then `-2`, `-3`, … while that name exists). Blocking; the repository
 * calls it on its I/O dispatcher.
 */
internal class SettingsFile(private val directory: Path, private val clock: Clock) {

    /** The settings file. */
    val path: Path = directory.resolve(SettingsLocations.SETTINGS_FILE_NAME)

    /** The file's text, or null if it does not exist. */
    fun read(): String? = if (Files.exists(path)) Files.readString(path) else null

    /** Writes [text] atomically, creating [directory] if needed. */
    fun write(text: String) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, SettingsLocations.SETTINGS_FILE_NAME, ".tmp")
        try {
            Files.writeString(temporary, text)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Moves the file to a fresh backup name and returns it. */
    fun backUpCorruptFile(): Path {
        val stamp = BACKUP_TIME.withZone(clock.zone).format(clock.instant())
        val base = "${SettingsLocations.SETTINGS_FILE_NAME}.corrupt-$stamp"
        val backup = generateSequence(1) { it + 1 }
            .map { n -> directory.resolve(if (n == 1) base else "$base-$n") }
            .first { !Files.exists(it) }
        Files.move(path, backup)
        return backup
    }

    private companion object {
        private val BACKUP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
