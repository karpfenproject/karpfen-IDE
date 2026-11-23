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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless UI tests of the further levels of [ContextMenuPopup]: an entry with children opens its level on a click and
 * when the pointer rests on it, crossing another entry on the way to it keeps it open, a press on its entry keeps it
 * open, choosing an entry of that level runs it and closes both levels, a disabled line of a level runs nothing, and a
 * click outside closes every level. The place of a level beside its entry is checked on [ContextMenuLevelPosition].
 */
@OptIn(ExperimentalTestApi::class)
class ContextMenuPopupTest {

    /** The entries the menu ran, in order. */
    private val ran = ArrayList<String>()

    /** True while the menu is shown. */
    private var shown by mutableStateOf(true)

    private val actions = listOf(
        ContextMenuAction("Rename") { ran += "Rename" },
        ContextMenuAction(
            "New",
            listOf(
                ContextMenuAction("File") { ran += "File" },
                ContextMenuAction("Folder") { ran += "Folder" },
            ),
        ),
        ContextMenuAction("Paste", listOf(ContextMenuAction("Nothing to paste", isEnabled = false) { ran += "Nothing to paste" })),
    )

    /** Shows the menu at a point of an area of the window until it is dismissed. */
    private fun ComposeUiTest.showMenu() {
        setThemedContent {
            Box(Modifier.fillMaxSize().testTag(AREA)) {
                Box(Modifier.offset(40.dp, 40.dp).size(0.dp)) {
                    if (shown) ContextMenuPopup(actions, DpOffset.Zero) { shown = false }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `an entry with children opens its level on a click, and choosing an entry there runs it and closes both levels`() = runComposeUiTest {
        showMenu()
        onNodeWithText("Folder").assertDoesNotExist()

        onNodeWithText("New").performClick()
        waitForIdle()
        onNodeWithText("File").assertIsDisplayed()
        onNodeWithText("Folder").assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(listOf("Folder"), ran)
        assertTrue(!shown, "the menu is dismissed")
        onNodeWithText("New").assertDoesNotExist()
        onNodeWithText("File").assertDoesNotExist()
    }

    @Test
    fun `the level opens while the pointer rests on its entry and closes on another entry`() = runComposeUiTest {
        showMenu()

        onNodeWithText("New").performMouseInput { moveTo(center) }
        rest()
        onNodeWithText("File").assertIsDisplayed()

        onNodeWithText("Rename").performMouseInput { moveTo(center) }
        rest()
        onNodeWithText("File").assertDoesNotExist()
        onNodeWithText("New").assertIsDisplayed()
        assertEquals(emptyList(), ran, "resting on entries runs nothing")
    }

    @Test
    fun `crossing another entry on the way keeps the level open, and passing over an entry opens nothing`() = runComposeUiTest {
        showMenu()
        onNodeWithText("New").performMouseInput { moveTo(center) }
        rest()
        onNodeWithText("File").assertIsDisplayed()

        onNodeWithText("Paste").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(ContextMenuDefaults.HOVER_DELAY_MILLIS / 3)
        onNodeWithText("File").performMouseInput { moveTo(center) }
        rest()
        onNodeWithText("File").assertIsDisplayed()
        onNodeWithText("Nothing to paste").assertDoesNotExist()
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `pressing the entry of an open level keeps the level open`() = runComposeUiTest {
        showMenu()
        onNodeWithText("New").performMouseInput { moveTo(center) }
        rest()

        onNodeWithText("New").performMouseInput { press() }
        waitForIdle()
        onNodeWithText("File").assertIsDisplayed()
        onNodeWithText("New").performMouseInput { release() }
        waitForIdle()
        onNodeWithText("File").assertIsDisplayed()
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `a level goes right of its entry and level with it, or else left of the menu and above, inside the window`() {
        val place = ContextMenuLevelPosition(padding = 8)
        val window = IntSize(1000, 800)
        val entry = IntRect(left = 100, top = 200, right = 300, bottom = 228)

        assertEquals(IntOffset(300, 192), place.calculatePosition(entry, window, LayoutDirection.Ltr, IntSize(200, 300)))

        val nearRight = IntRect(left = 700, top = 200, right = 900, bottom = 228)
        assertEquals(IntOffset(500, 192), place.calculatePosition(nearRight, window, LayoutDirection.Ltr, IntSize(200, 300)))

        val nearBottom = IntRect(left = 100, top = 700, right = 300, bottom = 728)
        assertEquals(IntOffset(300, 436), place.calculatePosition(nearBottom, window, LayoutDirection.Ltr, IntSize(200, 300)))

        val narrow = IntSize(450, 800)
        assertEquals(IntOffset(250, 192), place.calculatePosition(entry, narrow, LayoutDirection.Ltr, IntSize(200, 300)), "no side has room")
        assertEquals(IntOffset(300, 0), place.calculatePosition(entry, window, LayoutDirection.Ltr, IntSize(200, 800)), "as high as the window")
    }

    /** Lets the pointer rest where it is until the further levels follow it. */
    private fun ComposeUiTest.rest() {
        mainClock.advanceTimeBy(ContextMenuDefaults.HOVER_DELAY_MILLIS + 100L)
        waitForIdle()
    }

    @Test
    fun `a disabled line of a level is shown and runs nothing`() = runComposeUiTest {
        showMenu()

        onNodeWithText("Paste").performClick()
        waitForIdle()
        onNodeWithText("Nothing to paste").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Paste").assertIsEnabled()
        onNodeWithText("Nothing to paste").performClick()
        waitForIdle()

        assertEquals(emptyList(), ran)
    }

    @Test
    fun `a click outside every level closes them all`() = runComposeUiTest {
        showMenu()
        onNodeWithText("New").performClick()
        waitForIdle()
        onNodeWithText("File").assertIsDisplayed()

        onNodeWithTag(AREA).performMouseInput { click(Offset(right - 4f, bottom - 4f)) }
        waitForIdle()

        assertTrue(!shown, "the menu is dismissed")
        onNodeWithText("File").assertDoesNotExist()
        assertEquals(emptyList(), ran)
    }

    private companion object {
        /** The area the menu is shown in. */
        const val AREA = "area"
    }
}
