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
package io.karpfen.ide.ui.foundation.keys.compose

import androidx.compose.ui.input.key.Key
import io.karpfen.ide.ui.foundation.keys.KeyCode

/**
 * Maps the toolkit-neutral [KeyCode]s to the physical Compose keys and back. Every [KeyCode] has exactly one Compose
 * key, which the menu accelerators use. A few further Compose keys report an existing code. The keypad's Enter, for
 * example, reports [KeyCode.ENTER].
 */
internal object ComposeKeyTable {

    private val composeKeyByCode: Map<KeyCode, Key> = mapOf(
        KeyCode.A to Key.A, KeyCode.B to Key.B, KeyCode.C to Key.C, KeyCode.D to Key.D, KeyCode.E to Key.E,
        KeyCode.F to Key.F, KeyCode.G to Key.G, KeyCode.H to Key.H, KeyCode.I to Key.I, KeyCode.J to Key.J,
        KeyCode.K to Key.K, KeyCode.L to Key.L, KeyCode.M to Key.M, KeyCode.N to Key.N, KeyCode.O to Key.O,
        KeyCode.P to Key.P, KeyCode.Q to Key.Q, KeyCode.R to Key.R, KeyCode.S to Key.S, KeyCode.T to Key.T,
        KeyCode.U to Key.U, KeyCode.V to Key.V, KeyCode.W to Key.W, KeyCode.X to Key.X, KeyCode.Y to Key.Y,
        KeyCode.Z to Key.Z,
        KeyCode.DIGIT_0 to Key.Zero, KeyCode.DIGIT_1 to Key.One, KeyCode.DIGIT_2 to Key.Two,
        KeyCode.DIGIT_3 to Key.Three, KeyCode.DIGIT_4 to Key.Four, KeyCode.DIGIT_5 to Key.Five,
        KeyCode.DIGIT_6 to Key.Six, KeyCode.DIGIT_7 to Key.Seven, KeyCode.DIGIT_8 to Key.Eight,
        KeyCode.DIGIT_9 to Key.Nine,
        KeyCode.F1 to Key.F1, KeyCode.F2 to Key.F2, KeyCode.F3 to Key.F3, KeyCode.F4 to Key.F4,
        KeyCode.F5 to Key.F5, KeyCode.F6 to Key.F6, KeyCode.F7 to Key.F7, KeyCode.F8 to Key.F8,
        KeyCode.F9 to Key.F9, KeyCode.F10 to Key.F10, KeyCode.F11 to Key.F11, KeyCode.F12 to Key.F12,
        KeyCode.ENTER to Key.Enter,
        KeyCode.ESCAPE to Key.Escape,
        KeyCode.TAB to Key.Tab,
        KeyCode.BACKSPACE to Key.Backspace,
        KeyCode.DELETE to Key.Delete,
        KeyCode.INSERT to Key.Insert,
        KeyCode.SPACE to Key.Spacebar,
        KeyCode.HOME to Key.MoveHome,
        KeyCode.END to Key.MoveEnd,
        KeyCode.PAGE_UP to Key.PageUp,
        KeyCode.PAGE_DOWN to Key.PageDown,
        KeyCode.LEFT to Key.DirectionLeft,
        KeyCode.RIGHT to Key.DirectionRight,
        KeyCode.UP to Key.DirectionUp,
        KeyCode.DOWN to Key.DirectionDown,
        KeyCode.SLASH to Key.Slash,
        KeyCode.BACKSLASH to Key.Backslash,
        KeyCode.MINUS to Key.Minus,
        KeyCode.EQUALS to Key.Equals,
        KeyCode.COMMA to Key.Comma,
        KeyCode.PERIOD to Key.Period,
        KeyCode.SEMICOLON to Key.Semicolon,
        KeyCode.APOSTROPHE to Key.Apostrophe,
        KeyCode.GRAVE to Key.Grave,
        KeyCode.LEFT_BRACKET to Key.LeftBracket,
        KeyCode.RIGHT_BRACKET to Key.RightBracket,
    )

    private val codeByComposeKey: Map<Key, KeyCode> =
        composeKeyByCode.entries.associate { (code, key) -> key to code } + (Key.NumPadEnter to KeyCode.ENTER)

    /** The Compose key of [code]. */
    fun composeKeyOf(code: KeyCode): Key = composeKeyByCode.getValue(code)

    /** The code of the physical Compose [key], or null for keys without one, such as modifiers and keypad digits. */
    fun codeOf(key: Key): KeyCode? = codeByComposeKey[key]
}
