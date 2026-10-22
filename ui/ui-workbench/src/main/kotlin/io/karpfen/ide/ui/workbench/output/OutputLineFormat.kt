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
