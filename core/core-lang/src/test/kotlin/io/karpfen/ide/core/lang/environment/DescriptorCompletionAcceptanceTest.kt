package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.document.json.JsonParser
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.fixtures.CARET
import io.karpfen.ide.core.lang.services.ProviderFailure
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers what accepting a descriptor completion does to the text, applied the way the editor's completion session
 * applies an item: in strings without closing quote, in front of members and items that no comma separates, in key
 * strings without `:`, after the last item of a list and in an empty descriptor. Every result must be the expected
 * text, and on syntax-clean descriptors every proposal at every offset must keep the text syntax-clean.
 */
class DescriptorCompletionAcceptanceTest {

    private val failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList())
    private val services = DescriptorFixture.services(failures)

    @AfterTest
    fun noProviderFailed() = assertEquals(emptyList(), failures.map { "${it.operation}: ${it.error}" })

    /** The items at the caret `‸` of [textWithCaret]. */
    private fun complete(textWithCaret: String): List<CompletionItem> {
        val (document, offset) = DescriptorFixture.at(textWithCaret)
        return services.complete(document, offset)
    }

    /**
     * The text after accepting the item [label] at the caret `‸` of [textWithCaret], with the caret after accepting
     * marked by `‸`.
     */
    private fun accept(textWithCaret: String, label: String): String {
        val text = textWithCaret.filterNot { it == CARET }
        val item = complete(textWithCaret).singleOrNull { it.label == label }
            ?: error("no single '$label' in ${complete(textWithCaret).map { it.label }}")
        return applied(text, item, marked = true)
    }

    /** [text] with [item] applied over its replace range, the caret after it marked by `‸` if [marked]. */
    private fun applied(text: String, item: CompletionItem, marked: Boolean = false): String {
        val range = item.replaceRange
        val caret = range.start + (item.caretOffsetInInsert ?: item.insertText.length)
        val result = text.replaceRange(range.start, range.end, item.insertText)
        return if (marked) result.substring(0, caret) + CARET + result.substring(caret) else result
    }

    /** Asserts that [text] (caret marker removed) is one valid JSON value. */
    private fun assertClean(text: String) {
        val diagnostics = JsonParser.parse(text.filterNot { it == CARET }).diagnostics.map { it.message }
        assertEquals(emptyList(), diagnostics, text)
    }

    @Test
    fun `in a string without closing quote the rest of the line stays and the string is closed`() {
        val beforeComma = accept("{\n  \"metamodel\": \"‸,\n  \"model\": \"x.kmodel\"\n}", "cleaning_robot.kmeta")
        assertEquals("{\n  \"metamodel\": \"cleaning_robot.kmeta\"‸,\n  \"model\": \"x.kmodel\"\n}", beforeComma)

        assertEquals("{ \"model\": \"cleaning_robot.kmodel\"‸ }", accept("{ \"model\": \"cl‸ }", "cleaning_robot.kmodel"))
        assertEquals(
            "{ \"stateMachines\": [ { \"file\": \"cleaning_robot.kstates\"‸ } ] }",
            accept("{ \"stateMachines\": [ { \"file\": \"cl‸ } ] }", "cleaning_robot.kstates"),
        )
        assertEquals("{ \"metamodel\": \"a.kmeta\", \"model\": \"‸\" }", accept("{ \"metamodel\": \"a.kmeta\", \"mo‸ }", "model"))
        assertEquals("{ \"model\": \"cleaning_robot.kmodel\"‸ }", accept("{ \"model\": \"clean‸ing }", "cleaning_robot.kmodel"))
        listOf(beforeComma, accept("{ \"model\": \"cl‸ }", "cleaning_robot.kmodel")).forEach(::assertClean)
    }

    @Test
    fun `a string without closing quote before a member on the next line gets the comma it needs`() {
        val result = accept("{\n  \"metamodel\": \"cl‸\n  \"model\": \"b.kmodel\"\n}", "cleaning_robot.kmeta")

        assertEquals("{\n  \"metamodel\": \"cleaning_robot.kmeta\"‸,\n  \"model\": \"b.kmodel\"\n}", result)
        assertClean(result)
    }

    @Test
    fun `a key added in front of another member brings the comma`() {
        val between = "{\n  \"metamodel\": \"a.kmeta\",\n  ‸\n  \"model\": \"b.kmodel\"\n}"
        assertEquals("{\n  \"metamodel\": \"a.kmeta\",\n  \"events\": \"‸\",\n  \"model\": \"b.kmodel\"\n}", accept(between, "events"))
        assertEquals(
            "{\n  \"metamodel\": \"a.kmeta\",\n  \"events\": \"‸\",\n  \"model\": \"b.kmodel\"\n}",
            accept("{\n  \"metamodel\": \"a.kmeta\",\n  ev‸\n  \"model\": \"b.kmodel\"\n}", "events"),
        )
        val atTheTop = accept("{\n  ‸\n  \"metamodel\": \"a.kmeta\"\n}", "model")
        assertEquals("{\n  \"model\": \"‸\",\n  \"metamodel\": \"a.kmeta\"\n}", atTheTop)
        assertEquals("{\"model\": \"‸\", \"metamodel\": \"a.kmeta\"}", accept("{‸\"metamodel\": \"a.kmeta\"}", "model"))
        assertClean(accept(between, "stateMachines"))
        assertClean(accept(between, "tickDelayMs"))
    }

    @Test
    fun `a list item added in front of another item brings the comma`() {
        assertEquals(
            "{ \"bridges\": [ \"mqtt-sim.kbridge.json\"‸, \"x.kbridge.json\" ] }",
            accept("{ \"bridges\": [ ‸ \"x.kbridge.json\" ] }", "mqtt-sim.kbridge.json"),
        )
        assertEquals(
            "{ \"bridges\": [\"mqtt-sim.kbridge.json\"‸, \"x.kbridge.json\"] }",
            accept("{ \"bridges\": [‸\"x.kbridge.json\"] }", "mqtt-sim.kbridge.json"),
        )
        assertEquals(
            "{ \"bridges\": [ \"mqtt-sim.kbridge.json\"‸, \"x.kbridge.json\" ] }",
            accept("{ \"bridges\": [ \"‸\" \"x.kbridge.json\" ] }", "mqtt-sim.kbridge.json"),
            "the quote typed first, which the editor closes",
        )
        assertClean(accept("{ \"stateMachines\": [ ‸ { \"file\": \"a.kstates\", \"attachedTo\": \"x\" } ] }", "{ … }"))
    }

    @Test
    fun `a key string without colon becomes a whole member, or gets the colon in front of its value`() {
        val quoted = accept("{\n  \"metamodel\": \"a.kmeta\",\n  \"‸\"\n}", "model")
        assertEquals("{\n  \"metamodel\": \"a.kmeta\",\n  \"model\": \"‸\"\n}", quoted)
        assertClean(quoted)

        assertEquals("{ \"metamodel\": \"a\", \"tickDelayMs\": 50‸ }", accept("{ \"metamodel\": \"a\", \"ti‸\" }", "tickDelayMs"))
        val beforeMember = accept("{\n  \"‸\"\n  \"metamodel\": \"a.kmeta\"\n}", "model")
        assertEquals("{\n  \"model\": \"‸\",\n  \"metamodel\": \"a.kmeta\"\n}", beforeMember)
        assertEquals("{ \"tickDelayMs\":‸ 5 }", accept("{ \"ti‸\" 5 }", "tickDelayMs"))
        assertEquals("{ \"tickDelayMs‸\": 5 }", accept("{ \"ti‸\": 5 }", "tickDelayMs"), "a key with its colon is renamed")
    }

    @Test
    fun `an unquoted key with a colon gets its quoted name, and its colon and value stay`() {
        val first = accept("{ mo‸: \"x\" }", "model")
        assertEquals("{ \"model\"‸: \"x\" }", first)
        val afterComma = accept("{ \"metamodel\": \"a\", mo‸: \"x\" }", "model")
        assertEquals("{ \"metamodel\": \"a\", \"model\"‸: \"x\" }", afterComma)
        val multiLine = accept("{\n  mo‸: \"x.kmodel\",\n  \"metamodel\": \"a\"\n}", "model")
        assertEquals("{\n  \"model\"‸: \"x.kmodel\",\n  \"metamodel\": \"a\"\n}", multiLine)
        val number = accept("{ ti‸: 5 }", "tickDelayMs")
        assertEquals("{ \"tickDelayMs\"‸: 5 }", number)
        listOf(first, afterComma, multiLine, number).forEach(::assertClean)
        assertEquals("{ \"model\": \"‸\" }", accept("{ mo‸ }", "model"), "without a colon the whole member is written")
    }

    @Test
    fun `after the last item of a list another item comes with the comma it needs`() {
        assertEquals(
            "{ \"bridges\": [ \"x.kbridge.json\" , \"mqtt-sim.kbridge.json\"‸ ] }",
            accept("{ \"bridges\": [ \"x.kbridge.json\" ‸ ] }", "mqtt-sim.kbridge.json"),
        )
        val machines = accept("{ \"stateMachines\": [ { \"file\": \"a\", \"attachedTo\": \"b\" } ‸ ] }", "{ … }")
        assertEquals(
            "{ \"stateMachines\": [ { \"file\": \"a\", \"attachedTo\": \"b\" } , { \"file\": \"‸\", \"attachedTo\": \"\" } ] }",
            machines,
        )
        assertEquals(listOf("numpy"), complete("{ \"runtime\": { \"pythonPackages\": [ \"scipy\" ‸ ] } }").map { it.label })
        assertEquals(emptyList(), complete("{ \"bridges\": [ \"x.kbridge.json\" ‸ \"y.kbridge.json\" ] }"), "a comma there is the user's")
    }

    @Test
    fun `an empty descriptor offers the descriptor object with its required keys`() {
        assertEquals("{ \"metamodel\": \"‸\", \"model\": \"\" }", accept("‸", "{ … }"))
        assertEquals("  { \"metamodel\": \"‸\", \"model\": \"\" }\n", accept("  ‸\n", "{ … }"))
        assertEquals(emptyList(), complete("me‸"), "a text that is no descriptor gets nothing")
    }

    @Test
    fun `every proposal at every offset of a clean descriptor keeps it clean`() {
        val sparse = "{\n  \"metamodel\": \"cleaning_robot.kmeta\",\n\n  \"model\": \"cleaning_robot.kmodel\",\n" +
            "  \"stateMachines\": [\n    { \"file\": \"cleaning_robot.kstates\", \"attachedTo\": \"turtle\" }\n  ],\n\n" +
            "  \"bridges\": [ ]\n}\n"
        val texts = listOf(DescriptorFixture.VALID, DescriptorExamples.text(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE), sparse)
        var accepted = 0
        texts.forEach { text ->
            assertTrue(JsonParser.parse(text).isSyntaxClean, text)
            for (offset in 0..text.length) {
                services.complete(DescriptorFixture.document(text), offset).forEach { item ->
                    val result = applied(text, item)
                    assertTrue(JsonParser.parse(result).isSyntaxClean, "'${item.label}' at $offset gives:\n$result")
                    accepted++
                }
            }
        }
        assertTrue(accepted > 1_000, "only $accepted proposals")
    }
}
