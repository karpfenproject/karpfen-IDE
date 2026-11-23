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
import io.karpfen.ide.runtime.session.plan.ProjectFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests the plain text format of the project file `.karpfenproject`. */
class ProjectSettingsFileTest {

    private val settings = ProjectSettings(
        theme = ThemeMode.DARK,
        uiScale = 1.1f,
        editorFontSize = 14f,
        toolWindows = mapOf("RUNTIME" to ToolWindowLayout(visible = true, size = 400f), "OUTLINE" to ToolWindowLayout(visible = false, size = 240f)),
        editorPanes = StoredPanes.single(listOf("lamp.kstates", "sub folder/lamp.kmeta"), "sub folder/lamp.kmeta"),
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
    fun `a detached tool window has the word detached after its size and reads back`() {
        val detached = ProjectSettings(toolWindows = mapOf("RUNTIME" to ToolWindowLayout(visible = true, size = 400f, detached = true)))
        val text = ProjectSettingsFile.format(detached)
        assertTrue("pane.runtime = shown 400 detached" in text.lines())
        assertEquals(detached, ProjectSettingsFile.parse(text))
        assertEquals(
            ToolWindowLayout(visible = false, size = 300f, detached = true),
            ProjectSettingsFile.parse("pane.runtime = hidden 300 DETACHED").toolWindows["RUNTIME"],
        )
        assertEquals(
            ToolWindowLayout(visible = true, size = 300f),
            ProjectSettingsFile.parse("pane.runtime = shown 300 floating").toolWindows["RUNTIME"],
            "another third word counts as docked",
        )
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
    fun `the result cache is one line after the capture while it is off and no line while it is on`() {
        val uncached = settings.copy(capture = true, resultCaching = false)

        val text = ProjectSettingsFile.format(uncached)

        assertEquals("resultCache = off", text.lines().dropWhile { !it.startsWith("capture") }.drop(1).first())
        assertEquals(uncached, ProjectSettingsFile.parse(text))
        assertEquals(true, ProjectSettings().resultCaching, "the cache is on by default")
        assertEquals(emptyList(), ProjectSettingsFile.format(settings).lines().filter { it.startsWith("resultCache") })
    }

    @Test
    fun `a result cache line that is not off counts as on`() {
        assertEquals(false, ProjectSettingsFile.parse("resultCache = OFF\n").resultCaching)
        assertEquals(true, ProjectSettingsFile.parse("resultCache = on\n").resultCaching)
        assertEquals(true, ProjectSettingsFile.parse("resultCache = no\n").resultCaching)
        assertEquals(emptyList(), ProjectSettingsFile.parse("resultCache = no\n").otherLines, "the line is known, so it is not kept")
    }

    @Test
    fun `a capture line that is not on counts as off`() {
        assertEquals(true, ProjectSettingsFile.parse("capture = ON\n").capture)
        assertEquals(false, ProjectSettingsFile.parse("capture = off\n").capture)
        assertEquals(false, ProjectSettingsFile.parse("capture = yes\n").capture)
        assertEquals(emptyList(), ProjectSettingsFile.parse("capture = yes\n").otherLines, "the line is known, so it is not kept")
    }

    @Test
    fun `a feature of the project is a feature line after the language features, read only with the one it requires`() {
        val parallel = settings.copy(languageFeatures = setOf(KstatesFeature.PARALLEL), projectFeatures = setOf(ProjectFeature.TRUE_PARALLEL))

        val text = ProjectSettingsFile.format(parallel)

        assertEquals(listOf("feature.parallel = on", "feature.trueParallel = on"), text.lines().filter { it.startsWith("feature.") })
        assertEquals(parallel, ProjectSettingsFile.parse(text))
        assertEquals(emptySet(), ProjectSettings().projectFeatures, "off by default")
        val alone = ProjectSettingsFile.parse("feature.trueParallel = on\n")
        assertEquals(emptySet(), alone.projectFeatures, "without splits and joins the line is dropped")
        assertEquals(emptyList(), alone.otherLines)
        assertEquals(emptySet(), ProjectSettingsFile.parse("feature.parallel = on\nfeature.trueParallel = off\n").projectFeatures)
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

    @Test
    fun `the file of a project with one pane is the same as without panes`() {
        val text = """
            # karpfen project settings. karpfen IDE reads and rewrites this file.
            theme = dark
            zoom = 110
            editorFontSize = 14
            pane.runtime = shown 400
            open = lamp.kstates
            open = sub folder/lamp.kmeta
            active = sub folder/lamp.kmeta
            bridge = http.kbridge.json
            """.trimIndent() + "\n"

        val read = ProjectSettingsFile.parse(text)

        assertEquals(StoredPanes.single(listOf("lamp.kstates", "sub folder/lamp.kmeta"), "sub folder/lamp.kmeta"), read.editorPanes)
        assertEquals(text, ProjectSettingsFile.format(read), "the file is written back byte for byte")
        assertEquals(listOf("open = a.kmeta"), ProjectSettingsFile.format(ProjectSettings(editorPanes = StoredPanes.single(listOf("a.kmeta"))))
            .lines().filter { it.startsWith("open") || it.startsWith("active") || it.startsWith("split") }, "no active line names none")
    }

    @Test
    fun `two panes side by side are a split line, numbered open and active lines and the active pane`() {
        val panes = StoredPanes(
            StoredPanes.Node.Split(
                StoredPanes.Orientation.ROW,
                0.5f,
                StoredPanes.Node.Pane(listOf("lamp.kstates", "lamp.kmeta"), "lamp.kmeta"),
                StoredPanes.Node.Pane(listOf("lamp.kstates"), "lamp.kstates"),
            ),
            activePane = 1,
        )

        val text = ProjectSettingsFile.format(settings.copy(editorPanes = panes))

        assertEquals(
            listOf(
                "split = row 50 (1, 2)",
                "open.1 = lamp.kstates",
                "open.1 = lamp.kmeta",
                "active.1 = lamp.kmeta",
                "open.2 = lamp.kstates",
                "active.2 = lamp.kstates",
                "activePane = 2",
            ),
            text.lines().dropWhile { !it.startsWith("split") }.takeWhile { !it.startsWith("bridge") },
        )
        assertEquals(panes, ProjectSettingsFile.parse(text).editorPanes)
    }

    @Test
    fun `a nested tree reads back with its orientations, shares and active tabs`() {
        val panes = StoredPanes(
            StoredPanes.Node.Split(
                StoredPanes.Orientation.ROW,
                0.375f,
                StoredPanes.Node.Pane(listOf("a.kstates"), "a.kstates"),
                StoredPanes.Node.Split(
                    StoredPanes.Orientation.COLUMN,
                    0.4f,
                    StoredPanes.Node.Pane(listOf("b.kmeta", "a.kstates"), "a.kstates"),
                    StoredPanes.Node.Pane(listOf("sub folder/c.kmodel")),
                ),
            ),
            activePane = 2,
        )

        val text = ProjectSettingsFile.format(ProjectSettings(editorPanes = panes))

        assertTrue("split = row 37.5 (1, column 40 (2, 3))" in text.lines())
        assertTrue("activePane = 3" in text.lines())
        assertEquals(ProjectSettings(editorPanes = panes), ProjectSettingsFile.parse(text))
    }

    @Test
    fun `a share is written in percent with at most one decimal`() {
        val panes = StoredPanes(
            StoredPanes.Node.Split(StoredPanes.Orientation.COLUMN, 0.62345f, StoredPanes.Node.Pane(listOf("a")), StoredPanes.Node.Pane(listOf("b"))),
        )

        val text = ProjectSettingsFile.format(ProjectSettings(editorPanes = panes))

        assertTrue("split = column 62.3 (1, 2)" in text.lines())
        assertEquals(0.623f, (ProjectSettingsFile.parse(text).editorPanes.root as StoredPanes.Node.Split).share, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `the tree may be spaced freely and its words are not case sensitive, and a share that makes no sense counts as half`() {
        val read = ProjectSettingsFile.parse(
            """
            split=ROW(1,Column  120  ( 2 ,3 ))
            open.1 = a
            open.2 = b
            open.3 = c
            activePane = 3
            """.trimIndent(),
        ).editorPanes

        val root = read.root as StoredPanes.Node.Split
        assertEquals(StoredPanes.Orientation.ROW, root.orientation)
        assertEquals(0.5f, root.share, "a missing share counts as half")
        assertEquals(0.5f, (root.second as StoredPanes.Node.Split).share, "a share above 100 counts as half")
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), read.panes.map { it.tabs })
        assertEquals(2, read.activePane)
    }

    @Test
    fun `a tree that cannot be read falls back to one pane with every tab, in the order of the pane numbers`() {
        val lines = "open.2 = b\nopen.1 = a\nopen.2 = a\nactive.2 = b\nactivePane = 2\n"
        val expected = StoredPanes.single(listOf("a", "b"), "b")

        assertEquals(expected, ProjectSettingsFile.parse(lines).editorPanes, "no tree at all")
        assertEquals(expected, ProjectSettingsFile.parse("split = row 50 (1, 2\n$lines").editorPanes, "an unclosed split")
        assertEquals(expected, ProjectSettingsFile.parse("split = row 50 (1, 1)\n$lines").editorPanes, "a pane named twice")
        assertEquals(expected, ProjectSettingsFile.parse("split = 1\n$lines").editorPanes, "a pane with tabs is missing")
        assertEquals(expected, ProjectSettingsFile.parse("split = diagonal 50 (1, 2)\n$lines").editorPanes, "an unknown orientation")
        assertEquals(expected, ProjectSettingsFile.parse("split = row 50 (1, 2) 3\n$lines").editorPanes, "words after the tree")
        assertEquals(expected, ProjectSettingsFile.parse("split = row 50 (0, 2)\n$lines").editorPanes, "a pane number that is not positive")
        assertEquals(expected, ProjectSettingsFile.parse("split = row\n$lines").editorPanes, "a split without parts")
        assertEquals(emptyList(), ProjectSettingsFile.parse("split = row 50 (1, 2\n$lines").otherLines, "the pane lines are known")
    }

    @Test
    fun `a tree nested deeper than the parser accepts falls back to one pane, and far less deep trees are read`() {
        fun nested(levels: Int) = (1..levels).joinToString(" ") { "row 50 ($it," } + " ${levels + 1}" + ")".repeat(levels)
        val tabs = (1..301).joinToString("\n") { "open.$it = f$it" }

        assertEquals(StoredPanes.single(listOf("a")), ProjectSettingsFile.parse("split = ${nested(1200)}\nopen.1 = a\n").editorPanes)
        assertEquals(301, ProjectSettingsFile.parse("split = ${nested(300)}\n$tabs\n").editorPanes.panes.size, "300 splits in a row")
    }

    @Test
    fun `an empty open line of a pane that the tree does not name keeps the tree`() {
        val read = ProjectSettingsFile.parse("split = row 50 (2, 3)\nopen.2 = a\nopen.3 = b\nopen.4 =\nopen =\n").editorPanes

        assertEquals(listOf(StoredPanes.Node.Pane(listOf("a")), StoredPanes.Node.Pane(listOf("b"))), read.panes)
    }

    @Test
    fun `the open and active lines without a number belong to pane 1`() {
        val read = ProjectSettingsFile.parse("split = column 50 (1, 2)\nopen = a\nopen.2 = b\nactive = a\n").editorPanes

        assertEquals(listOf(StoredPanes.Node.Pane(listOf("a"), "a"), StoredPanes.Node.Pane(listOf("b"))), read.panes)
        assertEquals(0, read.activePane, "without an active pane the first one is active")
    }

    @Test
    fun `a pane without tabs is dropped and the other part of its split takes its place`() {
        val read = ProjectSettingsFile.parse("split = row 30 (1, column 50 (2, 3))\nopen.1 = a\nopen.3 = c\nactivePane = 2\n").editorPanes

        assertEquals(
            StoredPanes(
                StoredPanes.Node.Split(StoredPanes.Orientation.ROW, 0.3f, StoredPanes.Node.Pane(listOf("a")), StoredPanes.Node.Pane(listOf("c"))),
                activePane = 0,
            ),
            read,
            "the active pane was dropped, so the first one is active",
        )
        assertEquals(StoredPanes.EMPTY, ProjectSettingsFile.parse("split = row 50 (1, 2)\nactivePane = 2\n").editorPanes)
    }

    @Test
    fun `pane values that make no sense are dropped and the defaults stand in`() {
        val read = ProjectSettingsFile.parse(
            """
            split = row 50 (1, 2)
            open.1 = a
            open.1 =
            open.1 = a
            active.1 = gone
            open.2 = b
            activePane = second
            open.x = c
            active.0 = a
            """.trimIndent(),
        )

        assertEquals(listOf(StoredPanes.Node.Pane(listOf("a")), StoredPanes.Node.Pane(listOf("b"))), read.editorPanes.panes)
        assertEquals(0, read.editorPanes.activePane)
        assertEquals(listOf("open.x = c", "active.0 = a"), read.otherLines, "keys without a pane number are not pane keys and are kept")
    }

    @Test
    fun `an active pane and an active line of a pane that is not in the tree are ignored`() {
        val read = ProjectSettingsFile.parse("split = row 50 (1, 2)\nopen.1 = a\nopen.2 = b\nactive.7 = a\nactivePane = 7\n").editorPanes

        assertEquals(listOf(listOf("a"), listOf("b")), read.panes.map { it.tabs })
        assertEquals(0, read.activePane)
    }
}
