package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The editor state's background work on virtual time, with the real tokenizer and parser: highlighting with the
 * semantic carry-over while the parse lags (decision 39), the tokens of the action context (decision 38), bracket
 * matching, go to definition with the bounded wait for a current parse, and closing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundPipelinesTest {

    private val machine = """
        STATEMACHINE ATTACHED TO "Robot" {
            STATES {
                INITIAL STATE "ready" { }
                STATE "observe" { }
            }
        }
    """.trimIndent()

    @Test
    fun `highlights overlay the semantic styles of a current parse`() = editorTest(machine) { harness ->
        advanceUntilIdle()

        val highlights = harness.state.highlights.value
        assertEquals(harness.text.snapshot.value.version, highlights.version)
        assertTrue(HighlightSpan(harness.rangeOf("observe"), HighlightStyle.STATE_NAME) in highlights.spans, "$highlights")
        assertTrue(HighlightSpan(harness.rangeOf("Robot"), HighlightStyle.TYPE_NAME) in highlights.spans)
    }

    @Test
    fun `a typed character keeps the semantic style of untouched names while the parse lags`() = editorTest(machine) { harness ->
        advanceUntilIdle()
        val parsed = assertNotNull(harness.session.parse.value)
        assertTrue(parsed.semanticSpans.any { it.kind == SemanticKind.STATE_NAME })
        harness.state.caret.moveTo(harness.offsetOf("ready"))

        harness.state.type("x")
        advanceTimeBy(10.milliseconds)
        runCurrent()

        assertEquals(parsed.version, harness.session.parse.value?.version, "the debounced parse has not run yet")
        val highlights = harness.state.highlights.value
        assertEquals(harness.text.snapshot.value.version, highlights.version, "fresh lexical spans of the new text")
        assertTrue(HighlightSpan(harness.rangeOf("observe"), HighlightStyle.STATE_NAME) in highlights.spans, "carried over: $highlights")
        assertTrue(HighlightSpan(harness.rangeOf("Robot"), HighlightStyle.TYPE_NAME) in highlights.spans)
        assertTrue(
            highlights.spans.none { it.range == harness.rangeOf("xready") && it.style == HighlightStyle.STATE_NAME },
            "the edited name is not stretched over the typed character",
        )

        advanceUntilIdle()
        assertTrue(HighlightSpan(harness.rangeOf("xready"), HighlightStyle.STATE_NAME) in harness.state.highlights.value.spans)
    }

    @Test
    fun `toggle comment right after Enter inside an EVAL body uses the tokens of the new text`() = editorTest(
        "STATE \"s\" {\n    DO {\n        SET(\"x\", EVAL {\n            return 1\n        })\n    }\n}\n",
    ) { harness ->
        advanceUntilIdle()
        harness.state.caret.moveTo(harness.offsetOf("return 1") + "return 1".length)

        assertTrue(harness.press(KeyCode.ENTER))
        assertTrue(harness.session.tokens.value.version < harness.text.snapshot.value.version, "the tokenize pipeline lags")
        assertTrue(harness.press(KeyCode.SLASH, KeyModifier.META))

        val line = harness.text.snapshot.value.let { it.lineText(it.lineIndex.lineOf(harness.state.caret.primary.position)) }
        assertTrue(line.trimStart().startsWith("#"), "an EVAL body line is commented with #: '$line'")
    }

    @Test
    fun `the bracket match follows the caret`() = editorTest(
        "( )",
        services = FakeLanguageServices(brackets = { _, offset -> if (offset == 0) BracketMatch(TextRange(0, 1), TextRange(2, 3)) else null }),
    ) { harness ->
        advanceUntilIdle()
        assertEquals(BracketMatchSnapshot(0, BracketMatch(TextRange(0, 1), TextRange(2, 3))), harness.state.bracketMatch.value)

        harness.state.caret.moveTo(1)
        advanceUntilIdle()
        assertEquals(BracketMatchSnapshot(0, null), harness.state.bracketMatch.value)
    }

    @Test
    fun `go to definition waits for a parse of the current text and emits the first location`() {
        val target = Location(Path.of("a.kmeta").toAbsolutePath(), TextRange(3, 4))
        val services = FakeLanguageServices(definitions = { _, _ -> listOf(target, target.copy(range = TextRange(5, 6))) })
        editorTest(machine, services = services) { harness ->
            val received = mutableListOf<Location>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.state.navigationRequests.toList(received) }
            advanceUntilIdle()
            harness.state.caret.moveTo(harness.offsetOf("observe"))
            harness.state.type("x")

            assertTrue(harness.state.execute(EditorActionIds.GO_TO_DEFINITION))
            advanceUntilIdle()

            assertEquals(listOf(target), received)
            val document = services.definitionRequests.single()
            assertEquals(harness.text.snapshot.value.version, document.currentParse?.version, "the lookup saw a current parse")
        }
    }

    @Test
    fun `go to definition without a target emits nothing`() = editorTest(machine) { harness ->
        val received = mutableListOf<Location>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.state.navigationRequests.toList(received) }

        harness.state.goToDefinition(3)
        advanceUntilIdle()

        assertEquals(emptyList(), received)
    }

    @Test
    fun `close cancels the background work and is idempotent`() = editorTest(machine) { harness ->
        advanceUntilIdle()
        val before = harness.state.highlights.value
        harness.state.overlays.openFind(FindBarMode.FIND)

        harness.state.close()
        harness.state.close()
        harness.text.apply(listOf(io.karpfen.ide.core.text.TextEdit.insert(0, "x")), io.karpfen.ide.core.text.EditOrigin.Typing)
        advanceUntilIdle()

        assertEquals(before, harness.state.highlights.value, "no highlighting after close")
        assertEquals(null, harness.state.overlays.findBar.value, "overlays closed")
    }
}
