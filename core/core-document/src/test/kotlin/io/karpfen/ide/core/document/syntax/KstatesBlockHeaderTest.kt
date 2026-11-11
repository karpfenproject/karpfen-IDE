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
package io.karpfen.ide.core.document.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

/** The one KStates block rule: which keyword introduces a block, and the kind of block it opens. */
class KstatesBlockHeaderTest {

    /** The header of space-separated words. `_` stands for any other token, such as a string, a comma or `->`. */
    private fun header(words: String): KstatesBlockHeader =
        KstatesBlockHeader.of(words.split(' ').filter { it.isNotEmpty() }.map { it.takeUnless { word -> word == "_" } })

    private fun read(words: String): Triple<String?, Int, KstatesBlockKind> =
        header(words).let { Triple(it.introducer, it.introducerIndex, it.kind) }

    @Test
    fun `action, condition and structure keywords open their kinds of block`() {
        assertEquals(Triple("DO", 0, KstatesBlockKind.ACTION), read("DO"))
        assertEquals(Triple("CONDITION", 0, KstatesBlockKind.CONDITION), read("CONDITION"))
        assertEquals(Triple("STATE", 1, KstatesBlockKind.OTHER), read("INITIAL STATE _"))
        assertEquals(Triple(null, -1, KstatesBlockKind.OTHER), read("_ _"), "no introducer: another block")
    }

    @Test
    fun `the last introducer outside parentheses counts, after earlier actions of the block`() {
        assertEquals(Triple("IF", 7, KstatesBlockKind.ACTION), read("_ ( _ _ _ ) _ IF IN SCOPE ( _ )"))
        assertEquals(Triple("IF", 0, KstatesBlockKind.ACTION), read("IF IN SCOPE ( STATE )"), "a keyword in parentheses never counts")
        assertEquals(Triple(null, -1, KstatesBlockKind.OTHER), read("_ ( _ _ EVAL"), "an EVAL inside a call's parentheses")
    }

    @Test
    fun `MACRO introduces a block as a definition, never as a call`() {
        assertEquals(Triple("WITH", 0, KstatesBlockKind.ACTION), read("WITH MACRO ( _ _ _ ) AS _"))
        assertEquals(Triple("MACRO", 0, KstatesBlockKind.OTHER), read("MACRO _"))
        assertEquals(Triple("MACRO", 1, KstatesBlockKind.OTHER), read("WITH MACRO"), "MACRO not followed by ( defines")
    }
}
