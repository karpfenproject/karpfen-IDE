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

import io.karpfen.ide.runtime.host.RuntimeHostKind
import kotlin.math.roundToInt

/**
 * The text of the project file `.karpfenproject`. The format is as plain as possible, so the file can be read, edited
 * by hand and merged in version control. Each line is `key = value`. A line that starts with `#` is a comment, and
 * empty lines mean nothing. For example:
 *
 * ```
 * theme = dark
 * zoom = 110
 * editorFontSize = 14
 * pane.runtime = shown 400
 * runtime.host = docker
 * open = lamp.kstates
 * open = lamp.kmeta
 * active = lamp.kstates
 * bridge = http.kbridge.json
 * ```
 *
 * `theme` is `light`, `dark` or `system`. `zoom` is the scale of the workbench in percent. `pane.<name>` is `shown` or
 * `hidden` followed by the size of the tool window in dp. `runtime.host` is `docker` or `url`, and `runtime.url` is
 * the address for the latter. Every open file has its own `open` line, in the order of the tabs. Every bridge that was
 * started with the last run has its own `bridge` line.
 *
 * Reading never fails. A line with a known key and a value that makes no sense is dropped, and the default stands in.
 * A line with a key that is not known is kept and written back, so files of a newer version survive.
 */
public object ProjectSettingsFile {

    /** What starts a comment line. */
    private const val COMMENT = '#'

    /** The first line of every file the IDE writes. */
    private const val HEADER = "# karpfen project settings. karpfen IDE reads and rewrites this file."

    /** The prefix of the keys of the tool windows. */
    private const val PANE = "pane."

    /** The percent in one whole. */
    private const val PERCENT = 100f

    /** The settings that [text] holds. */
    public fun parse(text: String): ProjectSettings {
        var settings = ProjectSettings()
        val open = ArrayList<String>()
        val bridges = ArrayList<String>()
        val others = ArrayList<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(COMMENT)) continue
            val key = trimmed.substringBefore('=').trim()
            val value = trimmed.substringAfter('=', "").trim()
            when {
                '=' !in trimmed -> others.add(trimmed)
                key == "theme" -> theme(value)?.let { settings = settings.copy(theme = it) }
                key == "zoom" -> value.toFloatOrNull()?.let { percent ->
                    settings = settings.copy(uiScale = (percent / PERCENT).coerceIn(Zoom.MIN_UI_SCALE, Zoom.MAX_UI_SCALE))
                }
                key == "editorFontSize" -> value.toFloatOrNull()?.let { size ->
                    settings = settings.copy(editorFontSize = size.coerceIn(Zoom.MIN_EDITOR_FONT_SIZE, Zoom.MAX_EDITOR_FONT_SIZE))
                }
                key.startsWith(PANE) -> pane(value)?.let { layout ->
                    settings = settings.copy(toolWindows = settings.toolWindows + (key.removePrefix(PANE).uppercase() to layout))
                }
                key == "runtime.host" -> host(value)?.let { settings = settings.copy(runtime = settings.runtime.copy(host = it)) }
                key == "runtime.url" -> if (value.isNotEmpty()) settings = settings.copy(runtime = settings.runtime.copy(url = value))
                key == "open" -> if (value.isNotEmpty() && value !in open) open.add(value)
                key == "active" -> if (value.isNotEmpty()) settings = settings.copy(activeFile = value)
                key == "bridge" -> if (value.isNotEmpty() && value !in bridges) bridges.add(value)
                else -> others.add(trimmed)
            }
        }
        return settings.copy(
            openFiles = open,
            activeFile = settings.activeFile?.takeIf { it in open },
            bridges = bridges,
            otherLines = others,
        )
    }

    /** The text of the file for [settings]. [parse] reads it back to the same settings. */
    public fun format(settings: ProjectSettings): String = buildString {
        appendLine(HEADER)
        appendLine("theme = ${settings.theme.name.lowercase()}")
        appendLine("zoom = ${(settings.uiScale * PERCENT).roundToInt()}")
        appendLine("editorFontSize = ${settings.editorFontSize.roundToInt()}")
        settings.toolWindows.toSortedMap().forEach { (name, layout) ->
            appendLine("$PANE${name.lowercase()} = ${if (layout.visible) "shown" else "hidden"} ${layout.size.roundToInt()}")
        }
        appendLine("runtime.host = ${settings.runtime.host.name.lowercase()}")
        appendLine("runtime.url = ${settings.runtime.url}")
        settings.openFiles.forEach { appendLine("open = $it") }
        settings.activeFile?.let { appendLine("active = $it") }
        settings.bridges.forEach { appendLine("bridge = $it") }
        settings.otherLines.forEach(::appendLine)
    }

    private fun theme(value: String): ThemeMode? = ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

    private fun host(value: String): RuntimeHostKind? = RuntimeHostKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

    /** The layout that `shown 400` or `hidden 180` describes, or null if the value is something else. */
    private fun pane(value: String): ToolWindowLayout? {
        val parts = value.split(Regex("\\s+"))
        val visible = when (parts.getOrNull(0)?.lowercase()) {
            "shown" -> true
            "hidden" -> false
            else -> return null
        }
        val size = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return ToolWindowLayout(visible, size)
    }
}
