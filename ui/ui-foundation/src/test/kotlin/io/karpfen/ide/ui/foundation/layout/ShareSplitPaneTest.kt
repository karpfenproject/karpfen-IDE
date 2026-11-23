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
package io.karpfen.ide.ui.foundation.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.karpfen.ide.ui.foundation.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headless UI tests of the share-based [ShareSplitPane]: layout by share, divider drag, bounds and container resizes. */
@OptIn(ExperimentalTestApi::class)
class ShareSplitPaneTest {

    /** A split in a box of [width] by [height] whose hoisted share is [share]. It records every reported share. */
    private class Harness(width: Dp, height: Dp, share: Float) {
        var width by mutableStateOf(width)
        var height by mutableStateOf(height)
        var share by mutableStateOf(share)
        val reported = mutableListOf<Float>()
    }

    private fun ComposeUiTest.split(orientation: SplitOrientation, harness: Harness) {
        setThemedContent {
            Box(Modifier.size(harness.width, harness.height)) {
                ShareSplitPane(
                    orientation = orientation,
                    share = harness.share,
                    onShareChange = {
                        harness.reported += it
                        harness.share = it
                    },
                    minShare = 0.1f,
                    maxShare = 0.9f,
                    first = { Box(Modifier.fillMaxSize().testTag("first")) },
                    second = { Box(Modifier.fillMaxSize().testTag("second")) },
                )
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.px(dp: Dp): Float = with(density) { dp.toPx() }

    private fun assertDp(expected: Dp, actual: Dp, what: String) {
        assertTrue((expected - actual).value in -1f..1f, "$what: expected $expected, was $actual")
    }

    @Test
    fun `a horizontal split gives the first part its share of the width`() = runComposeUiTest {
        split(SplitOrientation.HORIZONTAL, Harness(601.dp, 400.dp, 0.5f))

        assertDp(300.dp, bounds("first").width, "first width")
        assertDp(300.dp, bounds("second").width, "second width (1 dp divider)")
        assertDp(301.dp, bounds("second").left, "second left")
        assertDp(400.dp, bounds("first").height, "first height")
    }

    @Test
    fun `a vertical split gives the first part its share of the height`() = runComposeUiTest {
        split(SplitOrientation.VERTICAL, Harness(400.dp, 401.dp, 0.25f))

        assertDp(100.dp, bounds("first").height, "first height")
        assertDp(300.dp, bounds("second").height, "second height")
        assertDp(400.dp, bounds("second").width, "second width")
    }

    @Test
    fun `both parts keep their shares when the container grows`() = runComposeUiTest {
        val harness = Harness(401.dp, 300.dp, 0.25f)
        split(SplitOrientation.HORIZONTAL, harness)

        harness.width = 801.dp
        waitForIdle()

        assertDp(200.dp, bounds("first").width, "first width")
        assertDp(600.dp, bounds("second").width, "second width")
        assertEquals(listOf(), harness.reported, "a resize of the container reports nothing")
    }

    @Test
    fun `dragging the divider reports the new share on every frame`() = runComposeUiTest {
        val harness = Harness(401.dp, 300.dp, 0.5f)
        split(SplitOrientation.HORIZONTAL, harness)

        onNodeWithTag(ShareSplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            repeat(4) { moveBy(Offset(px(20.dp), 0f)) }
            release()
        }
        waitForIdle()

        assertEquals(4, harness.reported.size, "one report per drag frame: ${harness.reported}")
        assertEquals(0.7f, harness.share, 0.01f)
        assertDp(280.dp, bounds("first").width, "first width after the drag")
        assertDp(120.dp, bounds("second").width, "second width after the drag")
    }

    @Test
    fun `a drag beyond the bound stops at the bound`() = runComposeUiTest {
        val harness = Harness(400.dp, 401.dp, 0.5f)
        split(SplitOrientation.VERTICAL, harness)

        onNodeWithTag(ShareSplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, -px(300.dp)))
            release()
        }
        waitForIdle()

        assertEquals(0.1f, harness.share, 0.001f)
        assertDp(40.dp, bounds("first").height, "first height at the bound")
    }

    @Test
    fun `a hoisted share beyond the bounds is drawn at the bound`() = runComposeUiTest {
        split(SplitOrientation.HORIZONTAL, Harness(401.dp, 300.dp, 0.99f))

        assertDp(360.dp, bounds("first").width, "first width")
        assertDp(40.dp, bounds("second").width, "second width")
    }
}
