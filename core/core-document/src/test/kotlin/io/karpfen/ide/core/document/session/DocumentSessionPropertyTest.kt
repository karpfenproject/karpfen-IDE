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
package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.TestReporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Randomized session test with fixed seeds. It makes random edits, which are typing, deletions, replacements around
 * diagnostics and undo. It pauses for random times around the parse debounce and sets random external sets.
 *
 * After every step the merged diagnostics refer to the newest snapshot and lie inside it. They also equal
 * [mergeDiagnostics] of the current sources, each mapped with `EditableText.mapRange` from the version it was computed
 * for. The external sets are mapped from the snapshot of the previous step, where the session re-anchored them with
 * `ExternalDiagnosticsAnchors`. So they never expire with the short history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSessionPropertyTest {

    private val source = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kstates").text

    /** Deterministic parse diagnostics of [snapshot]: a PARSER error, a LEXER copy and a "missing `}`" at the end. */
    private fun parseOf(snapshot: TextSnapshot): ParseOutcome {
        val random = Random(snapshot.version)
        val length = snapshot.length
        val start = random.nextInt(length + 1)
        val range = TextRange(start, minOf(length, start + random.nextInt(12)))
        return outcome(
            snapshot.version,
            syntax = listOf(
                diagnostic(range, message = "parser v${snapshot.version}"),
                diagnostic(range, DiagnosticSource.LEXER, "lexer copy v${snapshot.version}"),
                diagnostic(TextRange.empty(length), message = "missing '}' v${snapshot.version}"),
            ),
            standalone = if (snapshot.version % 3 == 0L) listOf(diagnostic(range, DiagnosticSource.DSL_TOOLS)) else emptyList(),
        )
    }

    @Test
    fun `merged diagnostics always equal the mapped sources merged by the one rule`(reporter: TestReporter) {
        SEEDS.forEach { seed -> runTest { checkSeed(seed, reporter) } }
    }

    private fun TestScope.checkSeed(seed: Long, reporter: TestReporter) {
        val random = Random(seed)
        val text = EditableText(source, historyLimit = 16)
        val session = session(text, FakeParser(::parseOf))
        val external = HashMap<String, ExternalDiagnosticSet>()
        var parsesSeen = 0
        var dropped = 0
        runCurrent()

        repeat(STEPS) { step ->
            edit(text, random)
            if (random.nextInt(8) == 0) {
                val snapshot = text.snapshot.value
                val sourceName = SOURCES[random.nextInt(SOURCES.size)]
                val diagnostics = List(random.nextInt(3)) { randomDiagnostic(snapshot, random, "$sourceName at $step") }
                session.setExternalDiagnostics(sourceName, snapshot, diagnostics)
                if (diagnostics.isEmpty()) external.remove(sourceName) else external[sourceName] = ExternalDiagnosticSet(snapshot, diagnostics)
            }
            advanceTimeBy(random.nextInt(250).milliseconds)
            runCurrent()

            val current = text.snapshot.value
            val merged = session.diagnostics.value
            assertSame(current, merged.snapshot, "seed $seed step $step")
            merged.diagnostics.forEach { assertTrue(it.range.end <= current.length, "$it beyond ${current.length}, seed $seed step $step") }
            val expected = reference(text, session.tokens.value, session.parse.value, external.values)
            assertEquals(expected, merged.diagnostics, "seed $seed step $step")
            if (session.parse.value?.version == current.version) parsesSeen++
            dropped += external.values.sumOf { set -> set.diagnostics.count { text.mapRange(it.range, set.snapshot) == null } }
            external.replaceAll { _, set -> ExternalDiagnosticSet(current, mapped(text, set.diagnostics, set.snapshot)) }
        }
        assertEquals(0, dropped, "seed $seed: re-anchored external diagnostics never fall out of the history")
        reporter.publishEntry("seed $seed", "$STEPS steps, $parsesSeen with a current parse")
    }

    /**
     * [diagnostics] valid in [from], mapped to the current version of [text] with mapRange. Those that cannot be mapped
     * are dropped.
     */
    private fun mapped(text: EditableText, diagnostics: List<Diagnostic>, from: TextSnapshot): List<Diagnostic> =
        diagnostics.mapNotNull { d -> text.mapRange(d.range, from)?.let { d.copy(range = it) } }

    /** The reference: every source mapped with mapRange, then merged with mergeDiagnostics. */
    private fun reference(
        text: EditableText,
        tokens: TokenizeResult,
        parse: ParseOutcome?,
        external: Collection<ExternalDiagnosticSet>,
    ): List<Diagnostic> {
        fun map(diagnostics: List<Diagnostic>, version: Long, from: TextSnapshot? = null) = diagnostics.mapNotNull { d ->
            val range = if (from != null) text.mapRange(d.range, from) else text.mapRange(d.range, version, text.lineage)
            range?.let { d.copy(range = it) }
        }
        val mappedParse = parse?.copy(
            syntaxDiagnostics = map(parse.syntaxDiagnostics, parse.version),
            standaloneDiagnostics = map(parse.standaloneDiagnostics, parse.version),
        )
        return mergeDiagnostics(map(tokens.diagnostics, tokens.version), mappedParse, external.map { map(it.diagnostics, it.snapshot.version, it.snapshot) })
    }

    /**
     * Applies one random edit. It is typing, a deletion, a replacement by a string, an undo, or a closing brace at the
     * end of the text, where the empty "missing }" diagnostic sits.
     */
    private fun edit(text: EditableText, random: Random) {
        val length = text.snapshot.value.length
        when (random.nextInt(10)) {
            in 0..4 -> text.type(random.nextInt(length + 1), INSERTIONS[random.nextInt(INSERTIONS.size)])
            in 5..6 -> if (length > 0) {
                val start = random.nextInt(length)
                text.replace(TextRange(start, minOf(length, start + 1 + random.nextInt(20))), "")
            }
            7 -> if (length > 0) {
                val start = random.nextInt(length)
                text.replace(TextRange(start, minOf(length, start + 1 + random.nextInt(30))), "\"x\"")
            }
            8 -> text.type(length, "}")
            else -> text.undo.undo()
        }
    }

    private fun randomDiagnostic(snapshot: TextSnapshot, random: Random, message: String): Diagnostic {
        val start = random.nextInt(snapshot.length + 1)
        return diagnostic(TextRange(start, minOf(snapshot.length, start + random.nextInt(10))), DiagnosticSource.DSL_TOOLS, message)
    }

    private companion object {
        val SEEDS = listOf(1L, 7L, 2026L)
        const val STEPS = 1_500
        val SOURCES = listOf(ExternalDiagnosticSources.PROJECT, ExternalDiagnosticSources.FILE)
        val INSERTIONS = listOf("a", " ", "\n", "{", "}", "EVAL { x // y\n}", "\"s\"", "😀")
    }
}
