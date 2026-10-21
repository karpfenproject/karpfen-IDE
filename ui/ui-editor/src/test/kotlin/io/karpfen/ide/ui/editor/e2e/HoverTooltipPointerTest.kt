package io.karpfen.ide.ui.editor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.popup.EditorPopupTags
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.view.EditorUi
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The hover tooltip stays while the pointer is on it, with the real `EditorPopups` over `CodeEditor` (regression of the
 * review finding "hover-tooltip-hides-on-pointer-enter"): its long content can be scrolled, and it is hidden once the
 * pointer left it and the hovered text.
 */
@OptIn(ExperimentalTestApi::class)
class HoverTooltipPointerTest {

    private fun ComposeUiTest.textArea(): SemanticsNodeInteraction = onNodeWithTag(EditorE2eTags.TEXT_AREA)

    @Test
    fun `the hover tooltip stays while the pointer is on it and hides after it left`() = runComposeUiTest {
        val text = "alpha beta\n\tgamma delta\nepsilon\n"
        val beta = TextRange(text.indexOf("beta"), text.indexOf("beta") + 4)
        val services = FakeLanguageServices(
            hovers = { _, offset -> if (offset in beta.start until beta.end) Hover(beta, (1..40).joinToString("\n") { "hover line $it" }) else null },
        )
        val ui = EditorUi(this, services)
        val popups = HostRecordingPopups()
        ui.compose(text, slots = popups)
        mainClock.autoAdvance = false
        val onBeta = checkNotNull(checkNotNull(popups.host).boundsOf(beta.start + 1)).center

        textArea().performMouseInput { moveTo(onBeta) }
        mainClock.advanceTimeBy(CodeEditorState.HOVER_DELAY.inWholeMilliseconds + 100)
        assertNotNull(ui.state.overlays.hover.value, "shown after resting on beta")

        val area = textArea().getUnclippedBoundsInRoot()
        val tooltip = onNodeWithTag(EditorPopupTags.HOVER).getUnclippedBoundsInRoot()
        val target = Offset(
            ((tooltip.left + tooltip.right) / 2 - area.left).value * density.density,
            ((tooltip.top + tooltip.bottom) / 2 - area.top).value * density.density,
        )
        textArea().performMouseInput {
            for (step in 1..10) moveTo(onBeta + (target - onBeta) * (step / 10f))
        }
        mainClock.advanceTimeBy(1_000)
        assertNotNull(ui.state.overlays.hover.value, "the tooltip stays while the pointer is on it")

        textArea().performMouseInput { moveTo(Offset(target.x, 100_000f)) }
        mainClock.advanceTimeBy(1_000)
        assertNull(ui.state.overlays.hover.value, "hidden once the pointer left the tooltip and the hovered text")
    }
}
