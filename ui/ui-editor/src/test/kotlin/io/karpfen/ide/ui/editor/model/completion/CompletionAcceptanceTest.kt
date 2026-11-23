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
package io.karpfen.ide.ui.editor.model.completion

import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.text.CaretMemento
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/** What accepting an item does: the indentation of its lines, its placeholders and the caret after it. */
class CompletionAcceptanceTest {

    private val template = "TRANSITION \"a\" -> \"b\" {\n    CONDITION {\n        DONE\n    }\n}"

    /** The template with the placeholders `a` and `b`, replacing [range]. */
    private fun item(range: TextRange, caret: Int? = null) = CompletionItem(
        "TRANSITION", CompletionKind.SNIPPET, template, range, caret, "template", null, "5:transition",
        placeholders = listOf(TextRange(12, 13), TextRange(19, 20)),
    )

    /** The texts of the placeholders of [acceptance] in [text] after its edit. */
    private fun placeholderTexts(acceptance: CompletionAcceptance, text: String): List<String> {
        val after = text.replaceRange(acceptance.edit.range.start, acceptance.edit.range.end, acceptance.edit.newText)
        return acceptance.placeholders.map { after.substring(it.start, it.end) }
    }

    @Test
    fun `at depth zero the template is inserted as it is and its first placeholder is selected`() {
        val acceptance = CompletionAcceptance.of(item(TextRange(0, 3)), TextRange(0, 3), TextSnapshot("TRA", 0))

        assertEquals(template, acceptance.edit.newText)
        assertEquals(TextRange(0, 3), acceptance.edit.range)
        assertEquals(listOf("a", "b"), placeholderTexts(acceptance, "TRA"))
        assertEquals(CaretMemento(12, 13), acceptance.caretAfter)
        assertEquals(template.length, acceptance.end)
        assertEquals(TextRange(0, template.length), acceptance.inserted)
    }

    @Test
    fun `nested, every line after the first gets the indentation of the line the insertion starts on`() {
        val text = "TRANSITIONS {\n\t  TRA\n}"
        val range = TextRange(text.indexOf("TRA\n"), text.indexOf("TRA\n") + 3)
        val acceptance = CompletionAcceptance.of(item(range, caret = template.indexOf("DONE")), range, TextSnapshot(text, 0))

        assertEquals("TRANSITION \"a\" -> \"b\" {\n\t      CONDITION {\n\t          DONE\n\t      }\n\t  }", acceptance.edit.newText)
        assertEquals(listOf("a", "b"), placeholderTexts(acceptance, text))
        val after = text.replaceRange(range.start, range.end, acceptance.edit.newText)
        assertEquals(after.indexOf("DONE"), acceptance.end, "the end position moves with the indentation before it")
    }

    @Test
    fun `an item without placeholders puts the caret as before and keeps one-line text unchanged`() {
        val oneLine = CompletionItem("DO { }", CompletionKind.SNIPPET, "DO {  }", TextRange(4, 6), 5, null, null, "1:do")
        val acceptance = CompletionAcceptance.of(oneLine, TextRange(4, 6), TextSnapshot("    DO", 0))

        assertEquals("DO {  }", acceptance.edit.newText)
        assertEquals(CaretMemento.at(9), acceptance.caretAfter)
        assertEquals(emptyList(), acceptance.placeholders)
    }
}
