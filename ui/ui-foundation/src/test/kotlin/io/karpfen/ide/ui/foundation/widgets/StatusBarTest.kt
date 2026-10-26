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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headless UI tests of [StatusBar]: alignment of the item groups, clickable and plain items, tooltips and updates. */
@OptIn(ExperimentalTestApi::class)
class StatusBarTest {

    @Test
    fun `left items are aligned left in order and right items right in order`() = runComposeUiTest {
        setThemedContent {
            Box(Modifier.width(600.dp)) {
                StatusBar(
                    left = listOf(StatusBarItem("problems", "2 errors", icon = KarpfenIcons.Error), StatusBarItem("busy", "Analyzing")),
                    right = listOf(StatusBarItem("position", "Ln 3, Col 7"), StatusBarItem("encoding", "UTF-8")),
                )
            }
        }

        val bar = onNodeWithTag(StatusBarTestTags.BAR).getUnclippedBoundsInRoot()
        val bounds = { id: String -> onNodeWithTag(StatusBarTestTags.item(id)).assertIsDisplayed().getUnclippedBoundsInRoot() }
        assertEquals(600f, bar.width.value, 0.5f)
        assertTrue(bounds("problems").left < 10.dp, "first left item at the left edge: ${bounds("problems")}")
        assertTrue(bounds("problems").right <= bounds("busy").left)
        assertTrue(bounds("busy").right < bounds("position").left, "a gap between the groups")
        assertTrue(bounds("position").right <= bounds("encoding").left)
        assertTrue(bounds("encoding").right > 590.dp, "last right item at the right edge: ${bounds("encoding")}")
        onNodeWithText("2 errors").assertIsDisplayed()
        onNodeWithText("Ln 3, Col 7").assertIsDisplayed()
    }

    @Test
    fun `an item with an action is a button that runs it, an item without one is not clickable`() = runComposeUiTest {
        var clicks = 0
        setThemedContent {
            StatusBar(
                left = listOf(StatusBarItem("problems", "1 warning", onClick = { clicks++ })),
                right = listOf(StatusBarItem("position", "Ln 1, Col 1")),
            )
        }

        onNodeWithTag(StatusBarTestTags.item("problems")).performClick()
        onNodeWithTag(StatusBarTestTags.item("problems")).performClick()
        assertEquals(2, clicks)
        onNodeWithTag(StatusBarTestTags.item("position")).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `an item with a tooltip shows it on hover`() = runComposeUiTest {
        setThemedContent {
            StatusBar(
                left = listOf(StatusBarItem("context", "Context: elevator", tooltip = "envs/elevator.kenv.json")),
                right = emptyList(),
            )
        }

        onNodeWithTag(StatusBarTestTags.item("context")).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(TooltipDefaults.DELAY_MILLIS + 200L)
        waitForIdle()

        onNodeWithText("envs/elevator.kenv.json").assertIsDisplayed()
    }

    @Test
    fun `new items replace the shown ones`() = runComposeUiTest {
        var right by mutableStateOf(listOf(StatusBarItem("position", "Ln 1, Col 1")))
        setThemedContent { StatusBar(left = emptyList(), right = right) }

        right = listOf(StatusBarItem("position", "Ln 12, Col 4"))
        waitForIdle()

        onNodeWithText("Ln 12, Col 4").assertIsDisplayed()
        onNodeWithText("Ln 1, Col 1").assertDoesNotExist()
    }
}
