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
package io.karpfen.ide.ui.foundation.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The [KarpfenIcons]: every icon exists once as a named 16 dp vector, glyphs are tintable black while file and severity
 * icons carry colours, every icon renders visible pixels headless, and material-icons-extended stays off the classpath.
 */
@OptIn(ExperimentalTestApi::class)
class KarpfenIconsTest {

    private val coloured = mapOf(
        "Folder" to KarpfenIcons.Folder,
        "File" to KarpfenIcons.File,
        "Kmeta" to KarpfenIcons.Kmeta,
        "Kmodel" to KarpfenIcons.Kmodel,
        "Kstates" to KarpfenIcons.Kstates,
        "Json" to KarpfenIcons.Json,
        "Error" to KarpfenIcons.Error,
        "Warning" to KarpfenIcons.Warning,
        "Info" to KarpfenIcons.Info,
    )
    private val glyphs = mapOf(
        "Close" to KarpfenIcons.Close,
        "ChevronRight" to KarpfenIcons.ChevronRight,
        "ChevronDown" to KarpfenIcons.ChevronDown,
        "Search" to KarpfenIcons.Search,
        "Detach" to KarpfenIcons.Detach,
        "Dock" to KarpfenIcons.Dock,
    )
    private val all = coloured + glyphs

    private fun paths(node: VectorNode): List<VectorPath> = when (node) {
        is VectorPath -> listOf(node)
        is VectorGroup -> node.flatMap(::paths)
    }

    private fun ImageVector.paintColors(): Set<Color> =
        paths(root).flatMap { listOfNotNull(it.fill, it.stroke) }.map { (it as SolidColor).value }.toSet()

    @Test
    fun `every icon of the IDE exists as a named 16 dp vector`() {
        assertEquals(15, all.size)
        all.forEach { (name, icon) ->
            assertEquals(name, icon.name)
            assertEquals(16.dp, icon.defaultWidth, name)
            assertEquals(16.dp, icon.defaultHeight, name)
            assertEquals(16f, icon.viewportWidth, name)
            assertEquals(16f, icon.viewportHeight, name)
            assertTrue(paths(icon.root).isNotEmpty(), "$name has no paths")
            assertTrue(paths(icon.root).all { it.pathData.isNotEmpty() }, "$name has an empty path")
        }
    }

    @Test
    fun `icons are built once`() {
        assertSame(KarpfenIcons.Folder, KarpfenIcons.Folder)
        assertSame(KarpfenIcons.Kstates, KarpfenIcons.Kstates)
        assertEquals(all.size, all.values.toSet().size, "every icon is a distinct vector")
    }

    @Test
    fun `glyph icons are black so they can be tinted, file and severity icons carry colours`() {
        glyphs.forEach { (name, icon) -> assertEquals(setOf(Color.Black), icon.paintColors(), name) }
        coloured.forEach { (name, icon) -> assertTrue(icon.paintColors().any { it != Color.Black && it != Color.White }, name) }
        val languageColours = listOf(KarpfenIcons.Kmeta, KarpfenIcons.Kmodel, KarpfenIcons.Kstates, KarpfenIcons.Json)
            .map { it.paintColors() - Color.White }
        assertEquals(4, languageColours.toSet().size, "the language icons differ in colour")
    }

    @Test
    fun `material-icons-extended is not on the classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("androidx.compose.material.icons.extended.R") }
        assertFailsWith<ClassNotFoundException> { Class.forName("androidx.compose.material.icons.filled.AccountTreeKt") }
    }

    @Test
    fun `every icon renders visible pixels headless`() = runComposeUiTest {
        setContent {
            Row {
                coloured.forEach { (name, icon) -> Image(icon, contentDescription = name, modifier = Modifier.testTag(name)) }
                glyphs.forEach { (name, icon) ->
                    Icon(icon, contentDescription = name, tint = Color.Red, modifier = Modifier.testTag(name))
                }
            }
        }

        all.keys.forEach { name ->
            val pixels = onNodeWithTag(name).captureToImage().toPixelMap()
            val painted = (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y -> pixels[x, y].alpha > 0.5f } }
            assertTrue(painted >= 10, "$name painted only $painted pixels")
        }
    }
}
