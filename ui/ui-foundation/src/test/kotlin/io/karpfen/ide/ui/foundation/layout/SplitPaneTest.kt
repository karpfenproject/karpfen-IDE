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
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
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

/** Headless UI tests of the size-based [SplitPane]: layout, divider drag, minimums, container resizes. */
@OptIn(ExperimentalTestApi::class)
class SplitPaneTest {

    /** A split in a box of [width] × [height] whose hoisted size is [size]; records every reported size. */
    private class Harness(width: Dp, height: Dp, size: Dp) {
        var width by mutableStateOf(width)
        var height by mutableStateOf(height)
        var size by mutableStateOf(size)
        val reported = mutableListOf<Dp>()
    }

    private fun ComposeUiTest.split(
        orientation: SplitOrientation,
        side: SplitSide,
        harness: Harness,
        minFirst: Dp = 80.dp,
        minSecond: Dp = 80.dp,
    ) {
        setThemedContent {
            Box(Modifier.size(harness.width, harness.height)) {
                SplitPane(
                    orientation = orientation,
                    fixedSide = side,
                    fixedSize = harness.size,
                    onFixedSizeChange = {
                        harness.reported += it
                        harness.size = it
                    },
                    minFirst = minFirst,
                    minSecond = minSecond,
                    first = { Box(Modifier.fillMaxSize().testTag("first")) },
                    second = { Box(Modifier.fillMaxSize().testTag("second")) },
                )
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.px(dp: Dp): Float = with(density) { dp.toPx() }

    private fun assertDp(expected: Dp, actual: Dp, what: String) {
        assertTrue((expected - actual).value in -0.5f..0.5f, "$what: expected $expected, was $actual")
    }

    @Test
    fun `a horizontal split gives the first pane its fixed width and the second the rest`() = runComposeUiTest {
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, Harness(600.dp, 400.dp, 200.dp))

        assertDp(200.dp, bounds("first").width, "first width")
        assertDp(399.dp, bounds("second").width, "second width (1 dp divider)")
        assertDp(201.dp, bounds("second").left, "second left")
        assertDp(400.dp, bounds("first").height, "first height")
        assertDp(400.dp, bounds("second").height, "second height")
    }

    @Test
    fun `a vertical split with the second pane fixed gives it its height`() = runComposeUiTest {
        split(SplitOrientation.VERTICAL, SplitSide.SECOND, Harness(400.dp, 500.dp, 150.dp))

        assertDp(150.dp, bounds("second").height, "second height")
        assertDp(349.dp, bounds("first").height, "first height")
        assertDp(350.dp, bounds("second").top, "second top")
        assertDp(400.dp, bounds("second").width, "second width")
    }

    @Test
    fun `dragging the divider reports the new size on every frame`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 200.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness)

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            repeat(5) { moveBy(Offset(px(10.dp), 0f)) }
            release()
        }
        waitForIdle()

        assertEquals(5, harness.reported.size, "one report per drag frame: ${harness.reported}")
        harness.reported.forEachIndexed { index, size -> assertDp(200.dp + 10.dp * (index + 1), size, "frame $index") }
        assertDp(250.dp, bounds("first").width, "first width after the drag")
        assertDp(349.dp, bounds("second").width, "second width after the drag")
    }

    @Test
    fun `dragging the divider of a fixed second pane shrinks it when moving towards it`() = runComposeUiTest {
        val harness = Harness(400.dp, 600.dp, 200.dp)
        split(SplitOrientation.VERTICAL, SplitSide.SECOND, harness)

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, px(60.dp)))
            release()
        }
        waitForIdle()

        assertDp(140.dp, harness.size, "reported size")
        assertDp(140.dp, bounds("second").height, "second height")
        assertDp(459.dp, bounds("first").height, "first height")
    }

    @Test
    fun `touch drags move the divider too`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 200.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness)

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performTouchInput {
            down(center)
            moveBy(Offset(px(-40.dp), 0f))
            up()
        }
        waitForIdle()

        assertDp(160.dp, harness.size, "reported size")
    }

    @Test
    fun `a secondary-button drag does not move the divider`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 200.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness)

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
            moveBy(Offset(px(40.dp), 0f))
            release(MouseButton.Secondary)
        }
        waitForIdle()

        assertEquals(emptyList(), harness.reported)
    }

    @Test
    fun `dragging stops at the minimum sizes of both panes and follows the pointer back`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 200.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness, minFirst = 100.dp, minSecond = 150.dp)

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(px((-300).dp), 0f))
        }
        waitForIdle()
        assertDp(100.dp, harness.size, "clamped at minFirst")
        assertDp(100.dp, bounds("first").width, "first width at its minimum")

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput { moveBy(Offset(px(600.dp), 0f)) }
        waitForIdle()
        assertDp(449.dp, harness.size, "clamped so the second pane keeps minSecond")
        assertDp(150.dp, bounds("second").width, "second width at its minimum")

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput {
            moveBy(Offset(px((-350).dp), 0f))
            release()
        }
        waitForIdle()
        assertDp(150.dp, harness.size, "back to start - 300 + 600 - 350 = 150")
    }

    @Test
    fun `resizing the container keeps the size of a fixed first pane`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 200.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness)

        harness.width = 900.dp
        waitForIdle()
        assertDp(200.dp, bounds("first").width, "first width in the wider container")
        assertDp(699.dp, bounds("second").width, "second width in the wider container")

        harness.width = 400.dp
        waitForIdle()
        assertDp(200.dp, bounds("first").width, "first width in the narrower container")
        assertDp(199.dp, bounds("second").width, "second width in the narrower container")
        assertEquals(emptyList(), harness.reported, "a container resize reports nothing")
    }

    @Test
    fun `resizing the container keeps the size of a fixed second pane`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 200.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.SECOND, harness)

        harness.width = 1000.dp
        waitForIdle()
        assertDp(200.dp, bounds("second").width, "second width in the wider container")
        assertDp(799.dp, bounds("first").width, "first width in the wider container")
        assertDp(800.dp, bounds("second").left, "second left in the wider container")
        assertEquals(emptyList(), harness.reported)
    }

    @Test
    fun `a container too small for the fixed size shrinks the pane without reporting and restores it later`() =
        runComposeUiTest {
            val harness = Harness(600.dp, 400.dp, 400.dp)
            split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness, minSecond = 300.dp)

            assertDp(299.dp, bounds("first").width, "first shrinks so the second keeps its minimum")
            assertDp(300.dp, bounds("second").width, "second at its minimum")

            harness.width = 900.dp
            waitForIdle()

            assertDp(400.dp, bounds("first").width, "the hoisted size is restored")
            assertEquals(emptyList(), harness.reported)
            assertDp(400.dp, harness.size, "the hoisted size never changed")
        }

    @Test
    fun `a drag after a container resize starts from the laid-out size`() = runComposeUiTest {
        val harness = Harness(600.dp, 400.dp, 400.dp)
        split(SplitOrientation.HORIZONTAL, SplitSide.FIRST, harness, minSecond = 300.dp)

        onNodeWithTag(SplitPaneTestTags.DIVIDER).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(px((-49).dp), 0f))
            release()
        }
        waitForIdle()

        assertDp(250.dp, harness.size, "299 laid out - 49")
    }
}
