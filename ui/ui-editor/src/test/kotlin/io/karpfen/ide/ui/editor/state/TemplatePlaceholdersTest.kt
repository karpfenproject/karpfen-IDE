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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The placeholders of an accepted template, driven through the key routing of the editor state: the indentation of the
 * insertion, the selection of the first placeholder, Tab and Shift+Tab, the end after the last one, the ranges following
 * the typed text, every way the placeholders end, the one undo step, the completion list keeping its keys, and Tab
 * indenting again when no placeholders are active. Also the reveal of a selected placeholder, completion over a selected
 * placeholder, Tab moving on from the caret, and placeholders whose text was deleted with their line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplatePlaceholdersTest {

    private val template = "STATE \"Name\" {\n    ENTRY {\n        SET(\"path\", \"value\")\n    }\n}"

    /** The template with the placeholders `Name`, `path` and `value`, inserted at [offset]. */
    private fun templateItem(offset: Int): CompletionItem {
        val placeholders = listOf("Name", "path", "value").map { TextRange.ofLength(template.indexOf(it), it.length) }
        return CompletionItem(
            "STATE with ENTRY", CompletionKind.SNIPPET, template, TextRange.empty(offset), null, "template", null, "5:state", placeholders,
        )
    }

    /** Language services that offer the template at every offset. */
    private fun offering() = FakeLanguageServices(completions = { _, offset -> listOf(templateItem(offset)) })

    /**
     * Opens completion at the caret and accepts the template with Enter. Afterwards the services offer nothing, so the
     * identifiers typed into the placeholders open no list.
     */
    private fun TestScope.accept(harness: EditorStateHarness) {
        assertTrue(harness.press(KeyCode.M, KeyModifier.CTRL))
        advanceUntilIdle()
        assertNotNull(harness.state.completion.state.value, "the list is open")
        assertTrue(harness.press(KeyCode.ENTER))
        harness.services.completions = { _, _ -> emptyList() }
        advanceUntilIdle()
    }

    /** The text the primary caret selects. */
    private fun EditorStateHarness.selected(): String = state.caret.primary.selection.let { plain.substring(it.start, it.end) }

    @Test
    fun `a template at depth zero is inserted as it is and selects its first placeholder`() = editorTest("", services = offering()) { harness ->
        accept(harness)

        assertEquals(template, harness.plain)
        assertEquals("Name", harness.selected())
        assertEquals(harness.rangeOf("Name"), harness.state.caret.primary.selection)
    }

    @Test
    fun `a nested template gets the indentation of its line on every further line`() =
        editorTest("STATES {\n    \n}", services = offering()) { harness ->
            harness.state.caret.moveTo(harness.offsetOf("    \n") + 4)
            accept(harness)

            assertEquals(
                "STATES {\n    STATE \"Name\" {\n        ENTRY {\n            SET(\"path\", \"value\")\n        }\n    }\n}",
                harness.plain,
            )
            assertEquals("Name", harness.selected())
            assertTrue(harness.press(KeyCode.TAB))
            assertEquals(harness.rangeOf("path"), harness.state.caret.primary.selection, "the placeholders moved with the indentation")
        }

    @Test
    fun `Tab and Shift+Tab move between the placeholders, and Tab after the last ends them at the end`() =
        editorTest("", services = offering()) { harness ->
            accept(harness)

            assertTrue(harness.press(KeyCode.TAB))
            assertEquals("path", harness.selected())
            assertTrue(harness.press(KeyCode.TAB))
            assertEquals("value", harness.selected())
            assertTrue(harness.press(KeyCode.TAB, KeyModifier.SHIFT))
            assertEquals("path", harness.selected())
            assertTrue(harness.press(KeyCode.TAB, KeyModifier.SHIFT))
            assertTrue(harness.press(KeyCode.TAB, KeyModifier.SHIFT), "Shift+Tab on the first placeholder stays there")
            assertEquals("Name", harness.selected())
            repeat(3) { harness.press(KeyCode.TAB) }

            assertFalse(harness.state.caret.primary.hasSelection)
            assertEquals(template.length, harness.state.caret.primary.position, "the end of the template")
            assertEquals(template, harness.plain)
            assertTrue(harness.press(KeyCode.TAB))
            assertNotEquals(template, harness.plain, "the placeholders ended, so Tab is the editor's again")
        }

    @Test
    fun `the placeholders follow the typed text, and an emptied one stays reachable`() = editorTest("", services = offering()) { harness ->
        accept(harness)

        harness.state.type("Lamp")
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("path", harness.selected())
        assertTrue(harness.press(KeyCode.BACKSPACE))
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("value", harness.selected())
        assertTrue(harness.press(KeyCode.TAB, KeyModifier.SHIFT))
        assertEquals(TextRange.empty(harness.offsetOf("SET(\"") + 5), harness.state.caret.primary.selection, "the emptied path")
        harness.state.type("bulb->lit")
        assertTrue(harness.press(KeyCode.TAB, KeyModifier.SHIFT))
        assertEquals("Lamp", harness.selected())
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("bulb->lit", harness.selected())
        assertTrue(harness.plain.startsWith("STATE \"Lamp\" {"))
    }

    @Test
    fun `Enter inside the template is a normal Enter and keeps the placeholders`() = editorTest("", services = offering()) { harness ->
        accept(harness)
        harness.state.type("Lamp")

        assertTrue(harness.press(KeyCode.ENTER))
        assertTrue(harness.plain.startsWith("STATE \"Lamp\n"), harness.plain)
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("path", harness.selected())
    }

    @Test
    fun `Escape ends the placeholders, and Tab indents again`() = editorTest("", services = offering()) { harness ->
        accept(harness)

        assertTrue(harness.press(KeyCode.ESCAPE))
        assertTrue(harness.press(KeyCode.TAB))

        assertNotEquals("path", harness.selected())
        assertNotEquals(template, harness.plain, "Tab indented")
    }

    @Test
    fun `the placeholders end when the caret is put outside the template`() =
        editorTest("// head\n", services = offering()) { harness ->
            harness.state.caret.moveTo(harness.plain.length)
            accept(harness)

            harness.state.pointerPressed(0, 1, emptySet())
            advanceUntilIdle()
            harness.state.caret.moveTo(harness.offsetOf("Name"))
            assertTrue(harness.press(KeyCode.TAB))

            assertNotEquals("path", harness.selected(), "back inside, the placeholders stay ended")
        }

    @Test
    fun `an edit outside the template ends the placeholders, even one that keeps the length`() =
        editorTest("// head\n", services = offering()) { harness ->
            harness.state.caret.moveTo(harness.plain.length)
            accept(harness)

            harness.text.apply(listOf(TextEdit(TextRange(0, 2), "##")), EditOrigin.Programmatic("test"))
            assertTrue(harness.press(KeyCode.TAB))

            assertNotEquals("path", harness.selected())
        }

    @Test
    fun `one undo removes the whole template and ends the placeholders`() = editorTest("STATES {\n    \n}", services = offering()) { harness ->
        harness.state.caret.moveTo(harness.offsetOf("    \n") + 4)
        accept(harness)

        assertTrue(harness.state.execute(EditorActionIds.UNDO))
        assertEquals("STATES {\n    \n}", harness.plain)
        assertTrue(harness.press(KeyCode.TAB))
        assertNotEquals("STATES {\n    \n}", harness.plain, "Tab indents, since the placeholders ended")
    }

    @Test
    fun `while the completion list is open it keeps Tab, and the accepted template takes over`() =
        editorTest("", services = offering()) { harness ->
            accept(harness)
            harness.state.type("x")
            harness.services.completions = { _, offset -> listOf(templateItem(offset)) }

            assertTrue(harness.press(KeyCode.M, KeyModifier.CTRL))
            advanceUntilIdle()
            assertNotNull(harness.state.completion.state.value)
            assertTrue(harness.press(KeyCode.TAB))

            assertEquals(2, Regex("ENTRY").findAll(harness.plain).count(), "Tab accepted the proposal")
            assertEquals("Name", harness.selected(), "the placeholders of the newer template are active")
            assertEquals(harness.plain.indexOf("Name"), harness.state.caret.primary.selection.start)
        }

    @Test
    fun `text typed right behind the template ends the placeholders`() = editorTest("", services = offering()) { harness ->
        accept(harness)
        harness.state.caret.moveTo(harness.plain.length)
        assertTrue(harness.press(KeyCode.ENTER))
        harness.state.type("STATE")
        harness.state.caret.moveTo(harness.offsetOf("Name"))

        assertTrue(harness.press(KeyCode.TAB))

        assertNotEquals("path", harness.selected(), "the text behind the template is not part of it")
    }

    @Test
    fun `text typed right before the template ends the placeholders`() =
        editorTest("// head\n", services = offering()) { harness ->
            harness.state.caret.moveTo(harness.plain.length)
            accept(harness)
            harness.state.caret.moveTo(harness.offsetOf("STATE"))
            harness.state.type("x")
            harness.state.caret.moveTo(harness.offsetOf("Name"))

            assertTrue(harness.press(KeyCode.TAB))

            assertNotEquals("path", harness.selected(), "the text before the template is not part of it")
        }

    @Test
    fun `Tab and Shift+Tab reveal the placeholder they select`() = editorTest("", services = offering()) { harness ->
        accept(harness)
        harness.state.scrollRequest.value?.let(harness.state::consumeScrollRequest)

        assertTrue(harness.press(KeyCode.TAB))

        assertEquals(harness.state.caret.primary.position, harness.state.scrollRequest.value?.offset)
    }

    @Test
    fun `completion opens on a selected placeholder and replaces it`() = editorTest("", services = offering()) { harness ->
        accept(harness)
        harness.services.completions = { _, offset ->
            listOf(CompletionItem("Lamp", CompletionKind.KEYWORD, "Lamp", TextRange.ofLength(offset, 4), null, null, null, "Lamp"))
        }

        assertTrue(harness.press(KeyCode.M, KeyModifier.CTRL))
        advanceUntilIdle()
        assertEquals("", assertNotNull(harness.state.completion.state.value, "the list is open").filterText)
        assertTrue(harness.press(KeyCode.ENTER))

        assertTrue(harness.plain.startsWith("STATE \"Lamp\" {"), harness.plain)
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("path", harness.selected(), "the placeholders stay active")
    }

    @Test
    fun `Tab moves on from the placeholder the caret stands in`() = editorTest("", services = offering()) { harness ->
        accept(harness)
        assertTrue(harness.press(KeyCode.TAB))
        assertEquals("path", harness.selected())

        harness.state.caret.moveTo(harness.offsetOf("Name") + 2)
        assertTrue(harness.press(KeyCode.TAB))

        assertEquals("path", harness.selected(), "the one after Name, not the one after path")
    }

    @Test
    fun `a placeholder whose line was deleted is no longer a stop`() = editorTest("", services = offering()) { harness ->
        accept(harness)
        val line = harness.offsetOf("        SET")
        harness.text.apply(listOf(TextEdit(TextRange(line, harness.offsetOf("    }")), "")), EditOrigin.Programmatic("test"))

        assertTrue(harness.press(KeyCode.TAB))

        assertFalse(harness.state.caret.primary.hasSelection, "Name was the only placeholder left")
        assertEquals(harness.plain.length, harness.state.caret.primary.position, "the end of the template")
    }

    @Test
    fun `closing the editor ends the placeholders`() = editorTest("", services = offering()) { harness ->
        accept(harness)

        harness.state.close()
        harness.press(KeyCode.TAB)

        assertNotEquals("path", harness.selected())
    }

    @Test
    fun `without a template Tab indents`() = editorTest("abc") { harness ->
        assertTrue(harness.press(KeyCode.TAB))
        assertNotEquals("abc", harness.plain)
        assertEquals("abc", harness.plain.trim())
    }
}
