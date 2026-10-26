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
package io.karpfen.ide.ui.workbench.output

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How the Language Tools Output writes a line, presentation only, so the composable just renders the texts: the time
 * in [zone] as `HH:mm:ss.SSS`, the level's label padded to the longest label, so the columns of the monospace view line
 * up, the logger's simple name and the message. The simple name is the part of the logger's name after its last dot,
 * the class that logged, since every logger of the language tools shares the same long package prefix.
 */
internal class OutputLineFormat(private val zone: ZoneId = ZoneId.systemDefault()) {

    /** The local time of [line] to the millisecond. */
    fun time(line: OutputLine): String = TIME.format(line.time.atZone(zone))

    /** The label of the level of [line], padded with spaces to the width of the longest label. */
    fun level(line: OutputLine): String = line.level.label.padEnd(LEVEL_WIDTH)

    /** The simple name of the logger of [line]. */
    fun logger(line: OutputLine): String = line.logger.substringAfterLast('.')

    private companion object {
        /** Hours, minutes, seconds and milliseconds. */
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        /** The length of the longest level label. */
        val LEVEL_WIDTH: Int = OutputLevel.entries.maxOf { it.label.length }
    }
}
