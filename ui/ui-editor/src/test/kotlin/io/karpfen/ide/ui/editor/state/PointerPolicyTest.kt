package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.Hover
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.editor.model.caret.Caret
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Compose-free pointer policy of the editor state (decision 36 of the work-package plan): clicks by count and
 * modifiers per platform, drag selection by characters, words and lines, the context-menu caret rule and focus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PointerPolicyTest {

    private val text = "alpha beta\ngamma delta\n"
    private val target = Location(Path.of("target.kstates").toAbsolutePath(), TextRange(1, 2))

    private fun navigating() = FakeLanguageServices(definitions = { _, _ -> listOf(target) })

    private fun completingX() = FakeLanguageServices(
        completions = { _, offset ->
            listOf(CompletionItem("x", CompletionKind.KEYWORD, "x", TextRange.empty(offset), null, null, null, "x"))
        },
    )

    /** Collects the navigation requests of [harness] from now on. */
    private fun TestScope.navigations(harness: EditorStateHarness): List<Location> {
        val received = mutableListOf<Location>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.state.navigationRequests.toList(received) }
        return received
    }

    @Test
    fun `one, two and three clicks place the caret, select the word and select the line`() = editorTest(text) { harness ->
        harness.state.pointerPressed(2, 1, emptySet())
        assertEquals(Caret.at(2), harness.state.caret.primary)

        harness.state.pointerPressed(7, 2, emptySet())
        assertEquals(harness.rangeOf("beta"), harness.state.caret.primary.selection)

        harness.state.pointerPressed(14, 3, emptySet())
        assertEquals(TextRange(11, 23), harness.state.caret.primary.selection, "the line with its break")

        harness.state.pointerPressed(2, 7, emptySet())
        assertEquals(TextRange(0, 11), harness.state.caret.primary.selection, "more clicks count as three")
    }

    @Test
    fun `Shift+click extends the selection from the anchor`() = editorTest(text) { harness ->
        harness.state.pointerPressed(2, 1, emptySet())
        harness.state.pointerPressed(8, 1, setOf(KeyModifier.SHIFT))

        assertEquals(Caret(2, 8), harness.state.caret.primary.copy(preferredColumn = null))
        harness.state.pointerDragged(14)
        assertEquals(TextRange(2, 14), harness.state.caret.primary.selection, "a drag after Shift+click keeps the anchor")
    }

    @Test
    fun `the primary modifier with one click goes to the definition on macOS`() = editorTest(text, services = navigating()) { harness ->
        val received = navigations(harness)
        harness.state.pointerPressed(2, 1, emptySet())

        harness.state.pointerPressed(8, 1, setOf(KeyModifier.META))
        advanceUntilIdle()

        assertEquals(listOf(target), received)
        assertEquals(Caret.at(2), harness.state.caret.primary, "the caret stays")
        harness.state.pointerDragged(16)
        assertEquals(Caret.at(2), harness.state.caret.primary, "a definition press starts no drag")
    }

    @Test
    fun `the primary modifier is Ctrl on Windows and Meta is no navigation there`() = editorTest(
        text,
        platform = HostPlatform.WINDOWS,
        services = navigating(),
    ) { harness ->
        val received = navigations(harness)

        harness.state.pointerPressed(8, 1, setOf(KeyModifier.META))
        advanceUntilIdle()
        assertEquals(emptyList(), received)
        assertEquals(Caret.at(8), harness.state.caret.primary)

        harness.state.pointerPressed(2, 1, setOf(KeyModifier.CTRL))
        advanceUntilIdle()
        assertEquals(listOf(target), received)
        assertEquals(Caret.at(8), harness.state.caret.primary)
    }

    @Test
    fun `a double click with the primary modifier selects the word`() = editorTest(text, services = navigating()) { harness ->
        val received = navigations(harness)
        harness.state.pointerPressed(7, 2, setOf(KeyModifier.META))
        advanceUntilIdle()

        assertEquals(emptyList(), received)
        assertEquals(harness.rangeOf("beta"), harness.state.caret.primary.selection)
    }

    @Test
    fun `dragging selects by characters, by words after a double click and by lines after a triple click`() = editorTest(text) { harness ->
        harness.state.pointerPressed(2, 1, emptySet())
        harness.state.pointerDragged(8)
        assertEquals(Caret(2, 8), harness.state.caret.primary.copy(preferredColumn = null))
        harness.state.pointerReleased()
        harness.state.pointerDragged(9)
        assertEquals(TextRange(2, 8), harness.state.caret.primary.selection, "no drag after the release")

        harness.state.pointerPressed(7, 2, emptySet())
        harness.state.pointerDragged(13)
        assertEquals(Caret(6, 16), harness.state.caret.primary.copy(preferredColumn = null), "to the end of 'gamma'")
        harness.state.pointerDragged(1)
        assertEquals(Caret(10, 0), harness.state.caret.primary.copy(preferredColumn = null), "back over 'alpha': 'beta' stays selected")
        harness.state.pointerReleased()

        harness.state.pointerPressed(14, 3, emptySet())
        harness.state.pointerDragged(3)
        assertEquals(Caret(23, 0), harness.state.caret.primary.copy(preferredColumn = null), "whole lines")
    }

    @Test
    fun `every press closes completion and the context menu, hides the hover and requests focus`() = editorTest(
        text,
        services = completingX(),
    ) { harness ->
        harness.state.execute(EditorActionIds.COMPLETION)
        advanceUntilIdle()
        assertNotNull(harness.state.completion.state.value)
        harness.state.openContextMenuAt(3, 1f, 2f)
        harness.state.consumeFocusRequest()

        harness.state.pointerPressed(5, 1, emptySet())

        assertNull(harness.state.completion.state.value)
        assertNull(harness.state.overlays.contextMenu.value)
        assertTrue(harness.state.focusRequested.value)
    }

    @Test
    fun `a right-click outside the selection moves the caret, inside it keeps the selection`() = editorTest(text) { harness ->
        harness.state.caret.select(0, 5)

        harness.state.openContextMenuAt(3, 10f, 20f)
        assertEquals(TextRange(0, 5), harness.state.caret.primary.selection)
        assertEquals(ContextMenuRequest(3, 10f, 20f), harness.state.overlays.contextMenu.value)

        harness.state.openContextMenuAt(8, 30f, 40f)
        assertEquals(Caret.at(8), harness.state.caret.primary)
        assertEquals(ContextMenuRequest(8, 30f, 40f), harness.state.overlays.contextMenu.value)
    }

    @Test
    fun `focus loss closes completion and the hover and updates isFocused`() = editorTest(
        text,
        services = completingX(),
    ) { harness ->
        harness.state.onFocusChanged(true)
        assertTrue(harness.state.isFocused.value)
        harness.state.execute(EditorActionIds.COMPLETION)
        advanceUntilIdle()
        harness.state.overlays.showHover(HoverContent(TextRange(0, 1), emptyList(), Hover(TextRange(0, 1), "h")))
        harness.state.overlays.openFind(FindBarMode.FIND)

        harness.state.onFocusChanged(false)

        assertFalse(harness.state.isFocused.value)
        assertNull(harness.state.completion.state.value)
        assertNull(harness.state.overlays.hover.value)
        assertEquals(FindBarMode.FIND, harness.state.overlays.findBar.value, "the find bar is a popup of its own")
    }
}
