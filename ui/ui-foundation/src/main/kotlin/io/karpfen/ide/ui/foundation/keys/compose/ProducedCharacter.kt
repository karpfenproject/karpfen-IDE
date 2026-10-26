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

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import io.karpfen.ide.ui.foundation.keys.LayoutAwareStrokes
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The character a key press produced on the current keyboard layout, as far as the event tells: its code point;
 * otherwise (Cmd/Ctrl may suppress the character) the native AWT event's key char; otherwise the character of the
 * native event's extended key code (the key's unshifted character on the layout; its key code where AWT did not set
 * one, as in synthesized events). Null if none is printable.
 */
internal object ProducedCharacter {

    /** The character [event] produced, or null. */
    fun of(event: KeyEvent): Char? {
        val awtEvent = event.awtEventOrNull
        return printable(event.utf16CodePoint)
            ?: awtEvent?.keyChar?.takeIf(LayoutAwareStrokes::isPrintable)
            ?: awtEvent?.let { ofExtendedKeyCode(keyCodeOf(it)) }
    }

    private fun keyCodeOf(event: AwtKeyEvent): Int =
        event.extendedKeyCode.takeIf { it != AwtKeyEvent.VK_UNDEFINED } ?: event.keyCode

    /** The character of an AWT extended key code: a Unicode key (`0x01000000 + char`) or a punctuation VK constant. */
    fun ofExtendedKeyCode(extendedKeyCode: Int): Char? =
        if (extendedKeyCode and UNICODE_KEY_FLAG != 0) {
            printable(extendedKeyCode and UNICODE_KEY_FLAG.inv())
        } else {
            charByVirtualKey[extendedKeyCode]
        }

    private fun printable(codePoint: Int): Char? =
        codePoint.takeIf { it in 1..Char.MAX_VALUE.code }?.toChar()?.takeIf(LayoutAwareStrokes::isPrintable)

    /** AWT marks extended key codes of keys without a VK constant with this bit (`KeyEvent.getExtendedKeyCodeForChar`). */
    private const val UNICODE_KEY_FLAG = 0x01000000

    private val charByVirtualKey: Map<Int, Char> = mapOf(
        AwtKeyEvent.VK_SLASH to '/',
        AwtKeyEvent.VK_DIVIDE to '/',
        AwtKeyEvent.VK_BACK_SLASH to '\\',
        AwtKeyEvent.VK_MINUS to '-',
        AwtKeyEvent.VK_SUBTRACT to '-',
        AwtKeyEvent.VK_EQUALS to '=',
        AwtKeyEvent.VK_COMMA to ',',
        AwtKeyEvent.VK_PERIOD to '.',
        AwtKeyEvent.VK_SEMICOLON to ';',
        AwtKeyEvent.VK_QUOTE to '\'',
        AwtKeyEvent.VK_BACK_QUOTE to '`',
        AwtKeyEvent.VK_OPEN_BRACKET to '[',
        AwtKeyEvent.VK_CLOSE_BRACKET to ']',
    )
}
