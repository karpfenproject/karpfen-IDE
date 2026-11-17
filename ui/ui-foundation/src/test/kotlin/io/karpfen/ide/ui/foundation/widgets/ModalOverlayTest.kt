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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Headless UI tests of [ModalOverlay]: a control of the panel takes a click during which the mouse moves a little, and
 * neither a click nor a turn of the wheel beside the panel reaches the content behind the overlay.
 */
@OptIn(ExperimentalTestApi::class)
class ModalOverlayTest {

    @Test
    fun `a button of the panel takes a click during which the mouse moves a few pixels`() = runComposeUiTest {
        var clicks = 0
        setThemedContent {
            ModalOverlay { Box(Modifier.size(80.dp, 30.dp).clickable { clicks++ }.testTag(BUTTON)) }
        }
        onNodeWithTag(BUTTON).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(3f, 2f))
            moveBy(Offset(-1f, 1f))
            release()
        }
        assertEquals(1, clicks)
    }

    @Test
    fun `a click and a turn of the wheel beside the panel do not reach the content behind the overlay`() = runComposeUiTest {
        var behind = 0
        var inside = 0
        val scroll = ScrollState(0)
        setThemedContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { behind++ }.verticalScroll(scroll)) { Spacer(Modifier.height(5000.dp)) }
                ModalOverlay { Box(Modifier.size(80.dp, 30.dp).clickable { inside++ }) }
            }
        }
        onRoot().performMouseInput {
            click(Offset(4f, 4f))
            scroll(5f)
        }
        waitForIdle()
        assertEquals(0, behind, "the click stays away from the content behind")
        assertEquals(0, scroll.value, "the wheel stays away from the content behind")
        assertEquals(0, inside, "a click beside the panel is not a click on its controls")
    }

    private companion object {
        /** The tag of the button in the panel. */
        const val BUTTON = "overlay.button"
    }
}
