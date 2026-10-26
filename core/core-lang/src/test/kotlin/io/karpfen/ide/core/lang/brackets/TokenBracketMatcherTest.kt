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
package io.karpfen.ide.core.lang.brackets

import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.text.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers matching `{}` and `()` on PUNCTUATION tokens: the preferred side, brackets that do not count, unmatched
 * brackets, and braces closing the parentheses left open in their block.
 */
class TokenBracketMatcherTest {

    private val matcher = TokenBracketMatcher()

    private fun match(name: String, text: String, offset: Int): BracketMatch? =
        matcher.match(ExampleProject.request(ExampleProject.document(Path.of("/ws/$name"), text)), offset)

    private fun pair(open: Int, close: Int) = BracketMatch(TextRange(open, open + 1), TextRange(close, close + 1))

    @Test
    fun `the bracket right of the offset is preferred, then the one left of it`() {
        val text = "type \"A\" \"d\" { prop(\"x\", \"number\") }"
        val open = text.indexOf('{')
        val call = text.indexOf('(')

        assertEquals(pair(open, text.lastIndexOf('}')), match("a.kmeta", text, open), "right of the offset")
        assertEquals(pair(open, text.lastIndexOf('}')), match("a.kmeta", text, open + 1), "left of the offset")
        assertEquals(pair(call, text.indexOf(')')), match("a.kmeta", text, call), "a parenthesis")
        assertEquals(pair(call, text.indexOf(')')), match("a.kmeta", text, text.indexOf(')') + 1), "from the closing side")
        assertEquals(pair(call, text.indexOf(')')), match("a.kmeta", text, call + 1), "right of `(` is a quote, left of it the `(`")
    }

    @Test
    fun `brackets in strings, comments and embedded Python do not count`() {
        val text = "SET(\"(\", EVAL { return {1: (2)} }) // )"
        val call = text.indexOf('(')

        assertEquals(pair(call, text.indexOf(") //")), match("a.kstates", text, call))
        assertNull(match("a.kstates", text, text.indexOf("(\"") + 2), "inside the string")
        assertNull(match("a.kstates", text, text.indexOf("{1") + 1), "a Python brace")
        val eval = text.indexOf("{ return")
        assertEquals(pair(eval, text.lastIndexOf('}')), match("a.kstates", text, eval))
    }

    @Test
    fun `unmatched brackets and positions without bracket have no match`() {
        assertNull(match("a.kmeta", "type \"A\" \"d\" {", 13))
        assertNull(match("a.kmeta", "type \"A\" \"d\" { }", 2))
        assertNull(match("a.kmodel", ")", 0))
        assertNull(match("a.kmodel", "", 0))
    }

    @Test
    fun `a closing brace closes the open parentheses inside its block`() {
        val text = "STATE \"s\" { SET(\"a\" }"

        assertEquals(pair(text.indexOf('{'), text.indexOf('}')), match("a.kstates", text, text.indexOf('}')))
        assertNull(match("a.kstates", text, text.indexOf('(')))
    }
}
