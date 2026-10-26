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
 * The characters denoted by the punctuation codes of [KeyCode]: the one table shared by the display text of shortcuts
 * and the layout-aware key adapter in `keys.compose`.
 */
internal object PunctuationKeys {

    private val charByKey: Map<KeyCode, Char> = mapOf(
        KeyCode.SLASH to '/',
        KeyCode.BACKSLASH to '\\',
        KeyCode.MINUS to '-',
        KeyCode.EQUALS to '=',
        KeyCode.COMMA to ',',
        KeyCode.PERIOD to '.',
        KeyCode.SEMICOLON to ';',
        KeyCode.APOSTROPHE to '\'',
        KeyCode.GRAVE to '`',
        KeyCode.LEFT_BRACKET to '[',
        KeyCode.RIGHT_BRACKET to ']',
    )

    private val keyByChar: Map<Char, KeyCode> = charByKey.entries.associate { (key, char) -> char to key }

    /** Every punctuation code. */
    val keys: Set<KeyCode> get() = charByKey.keys

    /** The character [key] denotes, or null if [key] is not a punctuation code. */
    fun charOf(key: KeyCode): Char? = charByKey[key]

    /** The punctuation code denoting [char], or null if [char] is not one of them. */
    fun keyOf(char: Char): KeyCode? = keyByChar[char]
}
