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
package io.karpfen.ide.ui.foundation.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layout-aware punctuation rule of [LayoutAwareStrokes] on German and US layouts: when Shift is dropped and when it
 * is kept, which keys stay physical, and which characters count as printable.
 */
class LayoutAwareStrokesTest {

    private val shift = KeyModifier.SHIFT
    private val meta = KeyModifier.META
    private val ctrl = KeyModifier.CTRL

    @Test
    fun `German Shift+7 producing a slash is SLASH without Shift`() {
        assertEquals(KeyStroke(KeyCode.SLASH), LayoutAwareStrokes.resolve(KeyCode.DIGIT_7, setOf(shift), '/'))
    }

    @Test
    fun `German Cmd+Shift+7 is the primary+slash binding`() {
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(meta)), LayoutAwareStrokes.resolve(KeyCode.DIGIT_7, setOf(meta, shift), '/'))
    }

    @Test
    fun `US Shift+slash producing a question mark keeps the physical key and Shift`() {
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(shift)), LayoutAwareStrokes.resolve(KeyCode.SLASH, setOf(shift), '?'))
    }

    @Test
    fun `Shift is kept when the produced character is the physical key's own`() {
        assertEquals(KeyStroke(KeyCode.SLASH, setOf(ctrl, shift)), LayoutAwareStrokes.resolve(KeyCode.SLASH, setOf(ctrl, shift), '/'))
    }

    /**
     * On a German layout Shift+0 produces `=` and Shift+comma produces `;`, so Shift is dropped. On macOS Option+5
     * produces `[`, and Option stays.
     */
    @Test
    fun `a punctuation character from another key is reported by character, other modifiers kept`() {
        assertEquals(KeyStroke(KeyCode.EQUALS, setOf(ctrl)), LayoutAwareStrokes.resolve(KeyCode.DIGIT_0, setOf(ctrl, shift), '='))
        assertEquals(KeyStroke(KeyCode.SEMICOLON), LayoutAwareStrokes.resolve(KeyCode.COMMA, setOf(shift), ';'))
        assertEquals(
            KeyStroke(KeyCode.LEFT_BRACKET, setOf(KeyModifier.ALT)),
            LayoutAwareStrokes.resolve(KeyCode.DIGIT_5, setOf(KeyModifier.ALT), '['),
        )
    }

    @Test
    fun `letters, digits and other keys stay physical whatever character they produce`() {
        assertEquals(KeyStroke(KeyCode.A, setOf(meta)), LayoutAwareStrokes.resolve(KeyCode.A, setOf(meta), 'a'))
        assertEquals(KeyStroke(KeyCode.L, setOf(KeyModifier.ALT)), LayoutAwareStrokes.resolve(KeyCode.L, setOf(KeyModifier.ALT), '@'))
        assertEquals(KeyStroke(KeyCode.DIGIT_7, setOf(shift)), LayoutAwareStrokes.resolve(KeyCode.DIGIT_7, setOf(shift), '&'))
        assertEquals(KeyStroke(KeyCode.ENTER), LayoutAwareStrokes.resolve(KeyCode.ENTER, emptySet(), null))
    }

    @Test
    fun `a key without code and without a punctuation character has no stroke`() {
        assertNull(LayoutAwareStrokes.resolve(null, setOf(shift), null))
        assertNull(LayoutAwareStrokes.resolve(null, emptySet(), '#'))
    }

    @Test
    fun `a punctuation character is reported even when the physical key is unknown`() {
        assertEquals(KeyStroke(KeyCode.MINUS), LayoutAwareStrokes.resolve(null, setOf(shift), '-'))
    }

    @Test
    fun `every punctuation code round-trips through its character`() {
        val punctuation = listOf(
            KeyCode.SLASH, KeyCode.BACKSLASH, KeyCode.MINUS, KeyCode.EQUALS, KeyCode.COMMA, KeyCode.PERIOD,
            KeyCode.SEMICOLON, KeyCode.APOSTROPHE, KeyCode.GRAVE, KeyCode.LEFT_BRACKET, KeyCode.RIGHT_BRACKET,
        )
        assertEquals(punctuation.toSet(), PunctuationKeys.keys)
        punctuation.forEach { key -> assertEquals(key, PunctuationKeys.charOf(key)?.let(PunctuationKeys::keyOf)) }
        assertNull(PunctuationKeys.charOf(KeyCode.A))
        assertNull(PunctuationKeys.keyOf('?'))
    }

    @Test
    fun `control characters, DEL, undefined and surrogates are not printable`() {
        assertTrue(LayoutAwareStrokes.isPrintable('/'))
        assertTrue(LayoutAwareStrokes.isPrintable(' '))
        assertTrue(LayoutAwareStrokes.isPrintable('ä'))
        assertFalse(LayoutAwareStrokes.isPrintable('\u001f'))
        assertFalse(LayoutAwareStrokes.isPrintable('\u007f'))
        assertFalse(LayoutAwareStrokes.isPrintable('￿'))
        assertFalse(LayoutAwareStrokes.isPrintable('\ud83d'))
    }
}
