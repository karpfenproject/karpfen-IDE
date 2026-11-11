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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.fixtures.DeepNesting
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parsers of the modeling languages keep the [NestingLimit]. A text that nests exactly [NestingLimit.MAX_DEPTH]
 * levels deep parses. One level more is a `nesting-too-deep` error at the brace that opens it. The error is the same
 * on every parse, whatever the state of the JIT compiler. Braces of string literals and comments do not count, but
 * those of EVAL code do.
 */
class NestingLimitTest {

    private val registry = defaultLanguageRegistry()

    /** The outcome of parsing [text] as a file named [fileName]. */
    private fun parse(text: String, fileName: String = "deep.kstates"): ParseOutcome =
        registry.supportFor(fileName)!!.parser.parse(TextSnapshot(text, 0))

    /** The nesting-too-deep diagnostics of [outcome]. */
    private fun tooDeep(outcome: ParseOutcome) = outcome.syntaxDiagnostics.filter { it.code == DiagnosticCodes.NESTING_TOO_DEEP }

    @Test
    fun `a text at the limit parses, and one level more is reported at the brace that opens it`() {
        val statesAtLimit = NestingLimit.MAX_DEPTH - DeepNesting.STRUCTURE_DEPTH
        val atLimit = parse(DeepNesting.kstates(statesAtLimit))
        val beyondText = DeepNesting.kstates(statesAtLimit + 1)

        val beyond = parse(beyondText)

        assertTrue(atLimit.isSyntaxClean, "${atLimit.syntaxDiagnostics.take(3)}")
        val diagnostic = beyond.syntaxDiagnostics.single()
        assertEquals(DiagnosticCodes.NESTING_TOO_DEEP to DiagnosticSource.PARSER, diagnostic.code to diagnostic.source)
        val opening = "STATE \"s${statesAtLimit + 1}\" {"
        assertEquals(beyondText.indexOf(opening) + opening.length - 1, diagnostic.range.start)
        assertEquals("{", beyondText.substring(diagnostic.range.start, diagnostic.range.end))
    }

    @Test
    fun `a text far beyond the limit is reported the same way on every parse`() {
        val text = DeepNesting.kstates(20_000)

        val outcomes = List(3) { parse(text) }

        assertEquals(1, tooDeep(outcomes.first()).size)
        assertEquals(List(3) { outcomes.first().syntaxDiagnostics }, outcomes.map { it.syntaxDiagnostics })
    }

    @Test
    fun `braces of string literals and comments do not count, those of EVAL code do`() {
        val braces = "{".repeat(NestingLimit.MAX_DEPTH + 1)
        val quoted = machineWith("SET(\"x\", \"$braces\")\n                // $braces")
        val code = machineWith("SET(\"x\", EVAL { $braces${"}".repeat(NestingLimit.MAX_DEPTH + 1)} })")

        assertEquals(emptyList(), tooDeep(parse(quoted)))
        assertEquals(1, tooDeep(parse(code)).size)
    }

    @Test
    fun `nested objects of a model keep the limit too`() {
        val model = buildString {
            append("make object \"n0\":\"Node\" {\n")
            for (level in 1..NestingLimit.MAX_DEPTH) append("has(\"child\") -> make object \"n$level\":\"Node\" {\n")
            repeat(NestingLimit.MAX_DEPTH + 1) { append("}\n") }
        }

        val diagnostic = tooDeep(parse(model, "deep.kmodel")).single()

        assertEquals("{", model.substring(diagnostic.range.start, diagnostic.range.end))
        assertTrue(model.lastIndexOf("make object \"n${NestingLimit.MAX_DEPTH}\"") < diagnostic.range.start)
    }

    /** A state machine whose initial state runs [rule] while it is active. */
    private fun machineWith(rule: String): String =
        "STATEMACHINE ATTACHED TO \"T\" {\n    STATES {\n        INITIAL STATE \"s\" {\n            DO {\n" +
            "                $rule\n            }\n        }\n    }\n    TRANSITIONS { }\n}\n"
}
