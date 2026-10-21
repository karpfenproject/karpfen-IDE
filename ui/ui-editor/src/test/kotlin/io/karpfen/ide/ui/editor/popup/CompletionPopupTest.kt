package io.karpfen.ide.ui.editor.popup

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.font.FontWeight
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import org.junit.jupiter.api.TestReporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * The completion list through the overlay contract (`EditorPopups.Popups` with a fake host and the real completion
 * model): kind badge, label and detail per row, at most 12 rows with the selection kept visible, keys staying with the
 * model while the text area keeps focus, mouse selection and acceptance, and the anchor at the caret (spec §7.2).
 */
@OptIn(ExperimentalTestApi::class)
class CompletionPopupTest {

    private fun ComposeUiTest.openCompletion(harness: PopupHarness) {
        runOnIdle { harness.model.completion.open() }
        waitForIdle()
    }

    private fun ComposeUiTest.heightOf(tag: String): Float = onNodeWithTag(tag).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun labels(count: Int) = List(count) { "item%02d".format(it) }

    @Test
    fun `nothing is shown while no completion session is open`() = popupTest("st", caretAt = 2) {
        onNodeWithTag(EditorPopupTags.COMPLETION).assertDoesNotExist()
    }

    @Test
    fun `each row shows the kind badge, the label and the detail`() = popupTest("") { harness ->
        harness.model.completionItems = { _, offset ->
            listOf(
                CompletionItem("state", CompletionKind.KEYWORD, "state", TextRange(offset, offset), null, null, null, "1"),
                CompletionItem("Robot", CompletionKind.TYPE, "Robot", TextRange(offset, offset), null, "domain.kmeta", null, "2"),
            )
        }

        openCompletion(harness)

        onNodeWithTag(EditorPopupTags.completionRow(0))
            .assert(hasText("kw"))
            .assert(hasContentDescription("keyword"))
            .assert(hasText("state"))
            .assertIsSelected()
        onNodeWithTag(EditorPopupTags.completionRow(1))
            .assert(hasText("T"))
            .assert(hasContentDescription("type"))
            .assert(hasText("Robot"))
            .assert(hasText("domain.kmeta"))
            .assertIsNotSelected()
    }

    @Test
    fun `the list shows at most 12 rows and keeps the selected row visible`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(30))
        openCompletion(harness)

        val shown = (0 until 30).filter { index ->
            onAllNodes(isCompletionRow).fetchSemanticsNodes().any { it.config[SemanticsProperties.TestTag] == EditorPopupTags.completionRow(index) } &&
                onNodeWithTag(EditorPopupTags.completionRow(index)).isDisplayed()
        }
        assertEquals((0 until 12).toList(), shown)
        assertEquals(12 * heightOf(EditorPopupTags.completionRow(0)), heightOf(EditorPopupTags.COMPLETION), 0.5f)

        runOnIdle { harness.model.completion.moveSelection(20) }

        onNodeWithTag(EditorPopupTags.completionRow(20)).assertIsDisplayed().assertIsSelected()
        onNodeWithTag(EditorPopupTags.completionRow(5)).assertDoesNotExist()

        runOnIdle { harness.model.completion.moveSelection(-20) }
        onNodeWithTag(EditorPopupTags.completionRow(0)).assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun `fewer items make a shorter list`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(3))
        openCompletion(harness)

        assertEquals(3 * heightOf(EditorPopupTags.completionRow(0)), heightOf(EditorPopupTags.COMPLETION), 0.5f)
    }

    @Test
    fun `keys move the selection through the model while the text area keeps focus`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(4))
        runOnIdle { harness.textArea.requestFocus() }
        openCompletion(harness)

        runOnIdle { assertTrue(harness.model.completion.handleKey(KeyStroke(KeyCode.DOWN))) }

        onNodeWithTag(EditorPopupTags.completionRow(1)).assertIsSelected()
        onNodeWithTag(TEXT_AREA).assertIsFocused()
    }

    @Test
    fun `a click selects a row without taking focus and a double click accepts it`() = popupTest("x = it", caretAt = 6) { harness ->
        val model = harness.model
        model.completionItems = completionItems(listOf("item", "itemCount", "items"))
        runOnIdle { harness.textArea.requestFocus() }
        openCompletion(harness)

        onNodeWithTag(EditorPopupTags.completionRow(2)).performMouseInput { click() }
        runOnIdle { assertEquals(2, model.completion.state.value?.selectedIndex) }
        onNodeWithTag(EditorPopupTags.completionRow(2)).assertIsSelected()
        onNodeWithTag(TEXT_AREA).assertIsFocused()

        onNodeWithTag(EditorPopupTags.completionRow(1)).performMouseInput { doubleClick() }

        runOnIdle {
            assertEquals("x = itemCount", model.plain)
            assertNull(model.completion.state.value, "accepting closes the session")
            assertTrue("editor.completion.accept" in model.executorLog, "accepted through the executor: ${model.executorLog}")
        }
        onNodeWithTag(EditorPopupTags.COMPLETION).assertDoesNotExist()
    }

    @Test
    fun `the list follows the filter while typing and emphasizes the matched prefix`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(listOf("parse", "print", "prop"))
        openCompletion(harness)
        onNodeWithTag(EditorPopupTags.completionRow(2)).assertExists()

        runOnIdle { harness.model.type("pri") }

        onNodeWithTag(EditorPopupTags.completionRow(0)).assert(hasText("print"))
        onNodeWithTag(EditorPopupTags.completionRow(1)).assertDoesNotExist()
        val label = onNodeWithTag(EditorPopupTags.completionRow(0)).fetchSemanticsNode().config[SemanticsProperties.Text]
            .single { it.text == "print" }
        val bold = assertNotNull(label.spanStyles.singleOrNull { it.item.fontWeight == FontWeight.Bold }, "an emphasized span")
        assertEquals(0 until 3, bold.start until bold.end)
    }

    @Test
    fun `the list is anchored below the caret`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(3))
        runOnIdle { harness.host.caretBounds = Rect(80f, 32f, 81f, 48f) }
        openCompletion(harness)

        val bounds = boundsInTextArea(EditorPopupTags.COMPLETION)
        assertEquals(80f / density.density, bounds.left.value, 0.5f)
        assertEquals(48f / density.density, bounds.top.value, 0.5f)
    }

    @Test
    fun `the list opens above the caret when there is no room below`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(20))
        val areaHeight = heightOf(TEXT_AREA) * density.density
        val caretTop = areaHeight - 20f
        runOnIdle { harness.host.caretBounds = Rect(16f, caretTop, 17f, caretTop + 16f) }
        openCompletion(harness)

        val bounds = boundsInTextArea(EditorPopupTags.COMPLETION)
        assertEquals(caretTop / density.density, bounds.bottom.value, 0.5f)
        assertEquals(16f / density.density, bounds.left.value, 0.5f)
    }

    @Test
    fun `the list is shifted left to stay inside the text area`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(2))
        val areaWidth = onNodeWithTag(TEXT_AREA).getUnclippedBoundsInRoot().let { (it.right - it.left).value }
        val caretX = areaWidth * density.density - 10f
        runOnIdle { harness.host.caretBounds = Rect(caretX, 0f, caretX + 1f, 16f) }
        openCompletion(harness)

        assertEquals(areaWidth, boundsInTextArea(EditorPopupTags.COMPLETION).right.value, 0.5f)
    }

    @Test
    fun `a list that cannot be shown because the caret is off screen is closed`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(2))
        openCompletion(harness)
        onNodeWithTag(EditorPopupTags.COMPLETION).assertExists()

        runOnIdle { harness.host.caretBounds = null }

        onNodeWithTag(EditorPopupTags.COMPLETION).assertDoesNotExist()
        runOnIdle { assertNull(harness.model.completion.state.value, "a hidden list would take Enter, Tab and the arrows unseen") }
    }

    @Test
    fun `a list whose caret is about to be revealed waits for it instead of closing`() = popupTest("") { harness ->
        harness.model.completionItems = completionItems(labels(2))
        runOnIdle {
            harness.host.caretBounds = null
            harness.host.revealPending = true
        }
        openCompletion(harness)

        onNodeWithTag(EditorPopupTags.COMPLETION).assertDoesNotExist()
        runOnIdle { assertNotNull(harness.model.completion.state.value, "the reveal will show the caret") }

        runOnIdle {
            harness.host.caretBounds = TestGeometry.cell(0, 0)
            harness.host.revealPending = false
        }
        onNodeWithTag(EditorPopupTags.COMPLETION).assertExists()
    }

    @Test
    fun `a long list composes only the visible rows`(reporter: TestReporter) = popupTest("") { harness ->
        harness.model.completionItems = completionItems(List(5_000) { "item%04d".format(it) })

        val elapsed = measureTime { openCompletion(harness) }

        val composed = onAllNodes(isCompletionRow).fetchSemanticsNodes().size
        assertTrue(composed in 12..14, "virtualized: $composed rows composed")
        reporter.publishEntry("completion list with 5,000 items: open and first frame (ms)", elapsed.inWholeMilliseconds.toString())
    }
}
