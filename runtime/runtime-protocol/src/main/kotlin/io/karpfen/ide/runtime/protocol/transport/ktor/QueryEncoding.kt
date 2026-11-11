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
package io.karpfen.ide.runtime.protocol.transport.ktor

/**
 * The percent-encoding of query names and values. The client does it itself so that it never depends on a library's
 * idea of a query. The unreserved characters of RFC 3986 stay as they are. Those are letters, digits, `-`, `.`, `_` and
 * `~`. Every other byte of the UTF-8 encoding becomes `%XX` with upper-case digits. So a space becomes `%20` and a `+`
 * becomes `%2B`, and the runtime never reads a `+` as a space. This matters because object keys and state names contain
 * spaces, such as `APB 2101`, `living room` or `full power`. The object holds no state.
 */
internal object QueryEncoding {

    /** The upper-case hexadecimal digits. */
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** The punctuation of the unreserved characters. */
    private const val UNRESERVED_PUNCTUATION = "-._~"

    /** The number of bits of a hexadecimal digit. */
    private const val NIBBLE = 4

    /** The mask of the low hexadecimal digit of a byte. */
    private const val LOW_NIBBLE = 0x0F

    /** The mask of a byte as an unsigned value. */
    private const val BYTE_MASK = 0xFF

    /** [text] percent-encoded. */
    fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (code < ASCII_LIMIT && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UNRESERVED_PUNCTUATION)) {
                append(char)
            } else {
                append('%').append(HEX_DIGITS[code shr NIBBLE]).append(HEX_DIGITS[code and LOW_NIBBLE])
            }
        }
    }

    /** The first code that is not ASCII. */
    private const val ASCII_LIMIT = 0x80
}
