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
package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headless UI tests of [TabStrip]: select, close (button and middle click), dirty dot, tooltip and overflow scrolling. */
@OptIn(ExperimentalTestApi::class)
class TabStripTest {

    /** The hoisted state of a strip under test; selecting applies the selection, closing only records it. */
    private class Harness(tabs: List<TabItem>, selectedId: String?) {
        var tabs by mutableStateOf(tabs)
        var selectedId by mutableStateOf(selectedId)
        val selected = mutableListOf<String>()
        val closed = mutableListOf<String>()
    }

    private val threeTabs = listOf(
        TabItem("a.kmeta", "a.kmeta", tooltip = "/project/a.kmeta"),
        TabItem("b.kmodel", "b.kmodel", isDirty = true),
        TabItem("c.kstates", "c.kstates"),
    )

    private fun manyTabs(count: Int) = List(count) { TabItem("t$it", "tab number $it") }

    private fun ComposeUiTest.strip(harness: Harness, width: Dp = 600.dp) {
        setThemedContent {
            Box(Modifier.width(width)) {
                TabStrip(
                    tabs = harness.tabs,
                    selectedId = harness.selectedId,
                    onSelect = { id ->
                        harness.selected += id
                        harness.selectedId = id
                    },
                    onClose = { id -> harness.closed += id },
                )
            }
        }
    }

    private fun ComposeUiTest.tab(id: String) = onNodeWithTag(TabStripTestTags.tab(id))

    /** Asserts that the tab [id] lies completely inside the strip's viewport. */
    private fun ComposeUiTest.assertTabInViewport(id: String) {
        val strip = onNodeWithTag(TabStripTestTags.STRIP).getUnclippedBoundsInRoot()
        val tab = tab(id).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(tab.left.value >= strip.left.value - 0.5f && tab.right.value <= strip.right.value + 0.5f, "$id: $tab in $strip")
    }

    @Test
    fun `tabs show their titles and the selected tab is marked selected`() = runComposeUiTest {
        strip(Harness(threeTabs, selectedId = "b.kmodel"))

        threeTabs.forEach { onNodeWithText(it.title).assertIsDisplayed() }
        tab("b.kmodel").assertIsSelected()
        tab("a.kmeta").assertIsNotSelected()
        tab("c.kstates").assertIsNotSelected()
    }

    @Test
    fun `clicking a tab selects it`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "a.kmeta")
        strip(harness)

        tab("c.kstates").performClick()
        waitForIdle()

        assertEquals(listOf("c.kstates"), harness.selected)
        tab("c.kstates").assertIsSelected()
        tab("a.kmeta").assertIsNotSelected()
        assertEquals(emptyList(), harness.closed)
    }

    @Test
    fun `the close button closes its tab without selecting it`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "a.kmeta")
        strip(harness)

        onNodeWithTag(TabStripTestTags.close("c.kstates"), useUnmergedTree = true).performClick()
        waitForIdle()

        assertEquals(listOf("c.kstates"), harness.closed)
        assertEquals(emptyList(), harness.selected)
        onNodeWithContentDescription("Close b.kmodel", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a middle click closes the tab without selecting it`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "a.kmeta")
        strip(harness)

        tab("b.kmodel").performMouseInput { click(button = MouseButton.Tertiary) }
        waitForIdle()

        assertEquals(listOf("b.kmodel"), harness.closed)
        assertEquals(emptyList(), harness.selected)
        tab("a.kmeta").assertIsSelected()
    }

    @Test
    fun `a middle click on the close button closes the tab once`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "a.kmeta")
        strip(harness)

        onNodeWithTag(TabStripTestTags.close("b.kmodel"), useUnmergedTree = true)
            .performMouseInput { click(button = MouseButton.Tertiary) }
        waitForIdle()

        assertEquals(listOf("b.kmodel"), harness.closed)
        assertEquals(emptyList(), harness.selected)
    }

    @Test
    fun `a secondary click neither selects nor closes`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "a.kmeta")
        strip(harness)

        tab("c.kstates").performMouseInput { click(button = MouseButton.Secondary) }
        waitForIdle()

        assertEquals(emptyList(), harness.selected)
        assertEquals(emptyList(), harness.closed)
    }

    @Test
    fun `only dirty tabs show the dirty dot, and it follows the state`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "a.kmeta")
        strip(harness)

        onNodeWithTag(TabStripTestTags.dirty("b.kmodel"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(TabStripTestTags.dirty("a.kmeta"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(TabStripTestTags.dirty("c.kstates"), useUnmergedTree = true).assertDoesNotExist()

        harness.tabs = harness.tabs.map { it.copy(isDirty = it.id == "a.kmeta") }
        waitForIdle()

        onNodeWithTag(TabStripTestTags.dirty("a.kmeta"), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag(TabStripTestTags.dirty("b.kmodel"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `hovering a tab with a tooltip shows the tooltip after the delay`() = runComposeUiTest {
        strip(Harness(threeTabs, selectedId = "a.kmeta"))

        tab("a.kmeta").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(TooltipDefaults.DELAY_MILLIS + 200L)
        waitForIdle()

        onNodeWithText("/project/a.kmeta").assertIsDisplayed()
    }

    @Test
    fun `overflowing tabs scroll, and the selected tab is scrolled into view`() = runComposeUiTest {
        val harness = Harness(manyTabs(30), selectedId = "t0")
        strip(harness, width = 400.dp)
        assertTabInViewport("t0")
        tab("t29").assertExists()
        assertTrue(tab("t29").getUnclippedBoundsInRoot().left > 400.dp, "the last tab starts beyond the strip")

        harness.selectedId = "t29"
        waitForIdle()
        assertTabInViewport("t29")
        assertEquals(400f, tab("t29").getUnclippedBoundsInRoot().right.value, 0.5f, "aligned with the nearer (right) edge")

        harness.selectedId = "t2"
        waitForIdle()
        assertTabInViewport("t2")
        assertEquals(0f, tab("t2").getUnclippedBoundsInRoot().left.value, 0.5f, "aligned with the nearer (left) edge")
    }

    @Test
    fun `a selection by click scrolls a partly visible tab fully into view`() = runComposeUiTest {
        val harness = Harness(manyTabs(30), selectedId = "t0")
        strip(harness, width = 400.dp)
        val partlyVisible = harness.tabs.first { tab(it.id).getUnclippedBoundsInRoot().right > 400.dp }.id

        tab(partlyVisible).performMouseInput { click(Offset(10f, centerY)) }
        waitForIdle()

        assertEquals(partlyVisible, harness.selectedId)
        assertTabInViewport(partlyVisible)
    }

    @Test
    fun `the mouse wheel scrolls overflowing tabs`() = runComposeUiTest {
        strip(Harness(manyTabs(30), selectedId = "t0"), width = 400.dp)
        val before = tab("t0").getUnclippedBoundsInRoot().left

        onNodeWithTag(TabStripTestTags.STRIP).performMouseInput { scroll(5f, ScrollWheel.Horizontal) }
        waitForIdle()
        val afterHorizontal = tab("t0").getUnclippedBoundsInRoot().left
        assertTrue(afterHorizontal < before, "the horizontal wheel scrolls: $before -> $afterHorizontal")

        onNodeWithTag(TabStripTestTags.STRIP).performMouseInput { scroll(5f, ScrollWheel.Vertical) }
        waitForIdle()
        val afterVertical = tab("t0").getUnclippedBoundsInRoot().left
        assertTrue(afterVertical < afterHorizontal, "the vertical wheel scrolls: $afterHorizontal -> $afterVertical")
    }

    @Test
    fun `closing the selected tab elsewhere removes it from the strip`() = runComposeUiTest {
        val harness = Harness(threeTabs, selectedId = "b.kmodel")
        strip(harness)

        harness.tabs = harness.tabs.filterNot { it.id == "b.kmodel" }
        harness.selectedId = "c.kstates"
        waitForIdle()

        tab("b.kmodel").assertDoesNotExist()
        tab("c.kstates").assertIsSelected()
    }
}
