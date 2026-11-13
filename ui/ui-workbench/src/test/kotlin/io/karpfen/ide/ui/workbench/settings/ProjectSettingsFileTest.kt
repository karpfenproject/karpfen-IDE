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

import io.karpfen.dsl.states.KstatesFeature
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the plain text format of the project file `.karpfenproject`. */
class ProjectSettingsFileTest {

    private val settings = ProjectSettings(
        theme = ThemeMode.DARK,
        uiScale = 1.1f,
        editorFontSize = 14f,
        toolWindows = mapOf("RUNTIME" to ToolWindowLayout(visible = true, size = 400f), "OUTLINE" to ToolWindowLayout(visible = false, size = 240f)),
        openFiles = listOf("lamp.kstates", "sub folder/lamp.kmeta"),
        activeFile = "sub folder/lamp.kmeta",
        bridges = listOf("http.kbridge.json", "sub folder/mqtt.kbridge.json"),
    )

    @Test
    fun `the file is one key and value per line`() {
        assertEquals(
            """
            # karpfen project settings. karpfen IDE reads and rewrites this file.
            theme = dark
            zoom = 110
            editorFontSize = 14
            pane.outline = hidden 240
            pane.runtime = shown 400
            open = lamp.kstates
            open = sub folder/lamp.kmeta
            active = sub folder/lamp.kmeta
            bridge = http.kbridge.json
            bridge = sub folder/mqtt.kbridge.json
            """.trimIndent() + "\n",
            ProjectSettingsFile.format(settings),
        )
    }

    @Test
    fun `what is written reads back to the same settings`() {
        assertEquals(settings, ProjectSettingsFile.parse(ProjectSettingsFile.format(settings)))
        assertEquals(ProjectSettings(), ProjectSettingsFile.parse(ProjectSettingsFile.format(ProjectSettings())))
    }

    @Test
    fun `each switched-on language feature is one line, in the order of the features, and reads back`() {
        val features = settings.copy(languageFeatures = setOf(KstatesFeature.PARALLEL, KstatesFeature.HISTORY, KstatesFeature.TICK_LIMIT))

        val text = ProjectSettingsFile.format(features)

        assertEquals(
            listOf("feature.history = on", "feature.tickLimit = on", "feature.parallel = on"),
            text.lines().filter { it.startsWith("feature.") },
        )
        assertEquals(features, ProjectSettingsFile.parse(text))
        assertEquals(emptySet(), ProjectSettings().languageFeatures, "all features are off by default")
        assertEquals(
            listOf("feature.breakpoints = on", "feature.macros = on"),
            ProjectSettingsFile.format(ProjectSettings(languageFeatures = setOf(KstatesFeature.MACROS, KstatesFeature.BREAKPOINTS)))
                .lines().filter { it.startsWith("feature.") },
        )
    }

    @Test
    fun `a feature line that is not on counts as off, and an unknown feature is kept`() {
        val read = ProjectSettingsFile.parse("feature.history = off\nfeature.macros = ON\nfeature.teleport = on\n")

        assertEquals(setOf(KstatesFeature.MACROS), read.languageFeatures)
        assertEquals(listOf("feature.teleport = on"), read.otherLines)
        assertEquals(listOf("feature.macros = on", "feature.teleport = on"), ProjectSettingsFile.format(read).lines().filter { it.startsWith("feature.") })
    }

    @Test
    fun `the capture is one line after the bridges while it is on and no line while it is off`() {
        val captured = settings.copy(capture = true)

        val text = ProjectSettingsFile.format(captured)

        assertEquals("capture = on", text.lines().dropWhile { !it.startsWith("bridge") }.dropWhile { it.startsWith("bridge") }.first())
        assertEquals(captured, ProjectSettingsFile.parse(text))
        assertEquals(false, ProjectSettings().capture, "runs are not captured by default")
        assertEquals(emptyList(), ProjectSettingsFile.format(settings).lines().filter { it.startsWith("capture") })
    }

    @Test
    fun `a capture line that is not on counts as off`() {
        assertEquals(true, ProjectSettingsFile.parse("capture = ON\n").capture)
        assertEquals(false, ProjectSettingsFile.parse("capture = off\n").capture)
        assertEquals(false, ProjectSettingsFile.parse("capture = yes\n").capture)
        assertEquals(emptyList(), ProjectSettingsFile.parse("capture = yes\n").otherLines, "the line is known, so it is not kept")
    }

    @Test
    fun `an empty file and a file of comments hold the defaults`() {
        assertEquals(ProjectSettings(), ProjectSettingsFile.parse(""))
        assertEquals(ProjectSettings(), ProjectSettingsFile.parse("# nothing here\n\n   # nor here\n"))
    }

    @Test
    fun `a value that makes no sense is dropped and the default stands in`() {
        val read = ProjectSettingsFile.parse(
            """
            theme = purple
            zoom = big
            editorFontSize = 900
            pane.runtime = maybe 400
            pane.outline = shown -3
            active = not-open.kmeta
            """.trimIndent(),
        )

        assertEquals(ProjectSettings(editorFontSize = Zoom.MAX_EDITOR_FONT_SIZE), read, "the font size is brought into its limits")
    }

    @Test
    fun `the runtime lines of an older version are lines the IDE does not know and are kept`() {
        val text = "runtime.host = url\nruntime.url = http://127.0.0.1:9000\n"

        val read = ProjectSettingsFile.parse(text)

        assertEquals(listOf("runtime.host = url", "runtime.url = http://127.0.0.1:9000"), read.otherLines)
        assertEquals(read, ProjectSettingsFile.parse(ProjectSettingsFile.format(read)))
    }

    @Test
    fun `lines of a newer version are kept and written back`() {
        val text = "theme = light\nbreakpoints = lamp.kstates:12\nsomething without a value\n"

        val read = ProjectSettingsFile.parse(text)

        assertEquals(ThemeMode.LIGHT, read.theme)
        assertEquals(listOf("breakpoints = lamp.kstates:12", "something without a value"), read.otherLines)
        assertEquals(read, ProjectSettingsFile.parse(ProjectSettingsFile.format(read)))
    }

    @Test
    fun `keys and values may be spaced freely and names are not case sensitive`() {
        val read = ProjectSettingsFile.parse("  theme=DARK  \nzoom   =   120\npane.Runtime = SHOWN   350\n")

        assertEquals(ThemeMode.DARK, read.theme)
        assertEquals(1.2f, read.uiScale)
        assertEquals(mapOf("RUNTIME" to ToolWindowLayout(visible = true, size = 350f)), read.toolWindows)
    }
}
