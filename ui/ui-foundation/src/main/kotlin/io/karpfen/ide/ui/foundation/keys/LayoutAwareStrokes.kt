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

/**
 * The layout-aware punctuation rule of the key adapter. It does not depend on Compose, so it can be unit-tested
 * without key events.
 *
 * A key press that produced one of the punctuation characters of [KeyCode] is reported as that character's code.
 * Shift is dropped when the layout needed it for the character, which is the case when the physical key is not the
 * character's own key. On a German layout Shift+7 produces `/`, which becomes [KeyCode.SLASH] without Shift. Every
 * other press is reported as its physical key with its modifiers. On a US layout Shift+/ produces `?`, which stays
 * SLASH with Shift.
 */
internal object LayoutAwareStrokes {

    /**
     * The stroke of a key press. Returns null when the pressed key has no code and the press produced no punctuation
     * character.
     *
     * @param physical the code of the pressed key, or null for a key without one, such as a lone modifier.
     * @param modifiers the physical modifiers held.
     * @param produced the character the press produced on the current layout, or null if it is not known.
     */
    fun resolve(physical: KeyCode?, modifiers: Set<KeyModifier>, produced: Char?): KeyStroke? {
        val punctuation = produced?.let(PunctuationKeys::keyOf)
        return when {
            punctuation == null -> physical?.let { KeyStroke(it, modifiers) }
            punctuation == physical -> KeyStroke(punctuation, modifiers)
            else -> KeyStroke(punctuation, modifiers - KeyModifier.SHIFT)
        }
    }

    /**
     * Whether [char] is a character a key press can produce as text. Control characters, surrogates and AWT's
     * undefined character are not.
     */
    fun isPrintable(char: Char): Boolean =
        char >= ' ' && char != DELETE_CHAR && char != UNDEFINED_CHAR && !char.isSurrogate()

    /** The DEL control character (U+007F), which the check against the space alone would let through. */
    private const val DELETE_CHAR = '\u007f'

    /** AWT's `KeyEvent.CHAR_UNDEFINED`. */
    private const val UNDEFINED_CHAR = '￿'
}
