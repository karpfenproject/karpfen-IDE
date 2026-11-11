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
 * The settings file `settings.json` in [directory] on disk. The class reads it, writes it atomically and moves a
 * corrupt file aside.
 *
 * An atomic write goes to a temporary file in the same directory first and then moves it over the settings file. Where
 * the file system has no atomic move, a plain replacing move is used. A corrupt file is renamed to
 * `settings.json.corrupt-<yyyyMMdd-HHmmss>`, with the time taken from [clock]. If that name exists, `-2`, `-3` and so
 * on are appended until the name is free.
 *
 * All calls block. The repository calls them on its I/O dispatcher.
 */
internal class SettingsFile(private val directory: Path, private val clock: Clock) {

    /** The settings file. */
    val path: Path = directory.resolve(SettingsLocations.SETTINGS_FILE_NAME)

    /** The file's text, or null if it does not exist. */
    fun read(): String? = if (Files.exists(path)) Files.readString(path) else null

    /** Writes [text] atomically and creates [directory] if needed. */
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

    /** Moves the file to a fresh backup name and returns the backup's path. */
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
