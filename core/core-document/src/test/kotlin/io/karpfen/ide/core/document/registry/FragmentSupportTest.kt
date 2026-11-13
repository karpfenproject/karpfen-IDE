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
package io.karpfen.ide.core.document.registry

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the support for a piece of a text: it is coloured like its language, and nothing finds fault with it for not
 * being a whole file.
 */
class FragmentSupportTest {

    private val whole = defaultLanguageRegistry().support(KarpfenLanguage.KSTATES)
    private val fragment = fragmentSupport(whole)
    private val actions = TextSnapshot("SET(\"mode\", \"on\")\nSET(\"bulb->watts\", EVAL { return 40 })", 0)

    @Test
    fun `a piece keeps the language, the tokens and the comment marker of the whole`() {
        assertEquals(KarpfenLanguage.KSTATES, fragment.language)
        assertEquals(whole.lineComment, fragment.lineComment)
        assertEquals(whole.tokenizer.tokenize(actions).tokens, fragment.tokenizer.tokenize(actions).tokens)
        assertTrue(fragment.tokenizer.tokenize(actions).tokens.isNotEmpty())
    }

    @Test
    fun `nothing checks a piece, although it is no whole state machine`() {
        assertFalse(whole.parser.parse(actions).isSyntaxClean, "the actions alone are no state machine")
        val outcome = fragment.parser.parse(actions)
        assertTrue(outcome.isSyntaxClean)
        assertEquals(emptyList(), outcome.outline)
        assertEquals(actions.version, outcome.version)
    }
}
