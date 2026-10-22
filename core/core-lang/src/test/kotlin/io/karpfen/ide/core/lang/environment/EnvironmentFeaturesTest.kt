package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.fixtures.ExamplePaths
import io.karpfen.ide.core.lang.fixtures.ExampleProject
import io.karpfen.ide.core.lang.fixtures.after
import io.karpfen.ide.core.lang.highlight.DefaultHighlightProvider
import io.karpfen.ide.core.lang.services.ProviderFailure
import io.karpfen.ide.core.text.TextRange
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers completion, hover, go to definition, bracket matching and highlighting of environment descriptors at concrete
 * carets of the descriptor fixtures, in the example workspace of [DescriptorFixture].
 */
class EnvironmentFeaturesTest {

    private val failures: MutableList<ProviderFailure> = Collections.synchronizedList(ArrayList())
    private val services = DescriptorFixture.services(failures)
    private val valid = DescriptorFixture.VALID
    private val robotDir = ExampleProject.pathOf(ExamplePaths.ROBOT_DIR)

    @AfterTest
    fun noProviderFailed() = assertEquals(emptyList(), failures.map { "${it.operation}: ${it.error}" })

    /** The completion items at the caret `‸` of [textWithCaret], in the editor's order for an empty filter. */
    private fun complete(textWithCaret: String): List<CompletionItem> {
        val (document, offset) = DescriptorFixture.at(textWithCaret)
        return services.complete(document, offset).sortedWith(compareBy({ it.sortKey }, { it.label }))
    }

    /** The labels of [items] with the best rank (the ones the list shows first). */
    private fun firstRanked(items: List<CompletionItem>): List<String> {
        val best = items.minOfOrNull { it.sortKey.substringBefore(':') } ?: return emptyList()
        return items.filter { it.sortKey.substringBefore(':') == best }.map { it.label }
    }

    @Test
    fun `blank space after a comma offers the missing keys, required first, each with a value template`() {
        val items = complete("{\n  \"metamodel\": \"cleaning_robot.kmeta\",\n  ‸\n}")

        assertEquals(
            listOf("model", "bridges", "events", "eventTtlMs", "observe", "runtime", "stateMachines", "tickDelayMs"),
            items.map { it.label },
        )
        val model = items.first()
        assertEquals(CompletionKind.PROPERTY, model.kind)
        assertEquals("\"model\": \"\"", model.insertText)
        assertEquals("\"model\": \"".length, model.caretOffsetInInsert)
        assertEquals("a file path, required", model.detail)
        assertTrue(model.documentation!!.contains(".kmodel"))
        assertEquals("\"stateMachines\": [ { \"file\": \"\", \"attachedTo\": \"\" } ]", items.single { it.label == "stateMachines" }.insertText)
        assertEquals("\"tickDelayMs\": 50", items.single { it.label == "tickDelayMs" }.insertText)
        assertEquals("\"observe\": { \"objects\": \"\" }", items.single { it.label == "observe" }.insertText)
    }

    @Test
    fun `inside a key string a key with its colon is renamed, and nested objects offer their own keys`() {
        val items = complete("{ \"metamodel\": \"a\", \"ti‸\": 5 }")
        val tick = items.single { it.label == "tickDelayMs" }
        assertEquals("tickDelayMs", tick.insertText)
        assertEquals(TextRange(21, 23), tick.replaceRange)
        assertTrue("metamodel" !in items.map { it.label })

        assertEquals(listOf("attachedTo"), complete("{ \"stateMachines\": [ { \"file\": \"x.kstates\", ‸ } ] }").map { it.label })
        assertEquals(listOf("objects", "domains"), complete("{ \"observe\": { ‸ } }").map { it.label })
        assertEquals(listOf("image", "pythonPackages"), complete("{ \"runtime\": {‸} }").map { it.label })
        assertEquals(emptyList(), complete("{ \"extra\": { ‸ } }"), "nothing is known below an unknown key")
    }

    @Test
    fun `after the last member a key comes with the comma it needs, before another member none is offered`() {
        val items = complete("{\n  \"metamodel\": \"a.kmeta\"\n  ‸\n}")

        val model = items.first()
        assertEquals("model" to ", \"model\": \"\"", model.label to model.insertText)
        assertEquals(", \"model\": \"".length, model.caretOffsetInInsert)
        assertEquals(listOf("file"), complete("{ \"stateMachines\": [ { \"attachedTo\": \"x\" ‸ } ] }").map { it.label })
        assertEquals(", \"file\": \"\"", complete("{ \"stateMachines\": [ { \"attachedTo\": \"x\" ‸ } ] }").single().insertText)
        assertEquals(emptyList(), complete("{ \"metamodel\": \"a.kmeta\" ‸ \"model\": \"b\" }"), "a comma before the next member is the user's")
        assertEquals(emptyList(), complete("{ \"metamodel\": ‸ }").filter { it.kind == CompletionKind.PROPERTY }, "a value goes here")
    }

    @Test
    fun `a bare word typed where a key goes is replaced by the quoted key`() {
        val (document, offset) = DescriptorFixture.at("{ meta‸ }")

        val item = services.complete(document, offset).single { it.label == "metamodel" }

        assertEquals(TextRange(2, 6), item.replaceRange)
        assertEquals("\"metamodel\": \"\"", item.insertText)
    }

    @Test
    fun `file paths are the files of the key's role, next to the descriptor first`() {
        assertEquals(listOf("cleaning_robot.kmeta"), firstRanked(complete("{ \"metamodel\": \"‸\" }")))
        val metamodels = complete("{ \"metamodel\": \"‸\" }").map { it.label }
        assertTrue("../delivery_drone_example/delivery_drone.kmeta" in metamodels, "$metamodels")
        assertTrue(metamodels.indexOf("EVENTS.kmeta") > metamodels.indexOf("../delivery_drone_example/delivery_drone.kmeta"), "$metamodels")
        assertTrue("../../karpfen-dsl-tools/metamodel_dsl_example.kmeta" in metamodels)
        assertEquals(listOf("EVENTS.kmeta"), firstRanked(complete("{ \"events\": \"‸\" }")))
        assertEquals(listOf("cleaning_robot.kmodel"), firstRanked(complete("{ \"model\": \"‸\" }")))
        assertEquals(listOf("cleaning_robot.kstates"), firstRanked(complete("{ \"stateMachines\": [ { \"file\": \"‸\" } ] }")))
        assertEquals(listOf("mqtt-sim.kbridge.json"), complete("{ \"bridges\": [ \"‸\" ] }").map { it.label })

        val outside = complete("{ \"model\": ‸ }").first()
        assertEquals("\"cleaning_robot.kmodel\"", outside.insertText, "quoted outside a string")
        assertEquals(CompletionKind.FILE, outside.kind)
        assertEquals("the model", outside.detail)
    }

    @Test
    fun `an event metamodel of another name is offered for the events, as the workspace accepts it there`() {
        val eventsFile = DescriptorFixture.PATH.resolveSibling("robot_events.kmeta")
        val features = EnvironmentLanguageFeatures(DefaultHighlightProvider(), EnvironmentSchema.ROOT)
        val (document, offset) = DescriptorFixture.at("{ \"events\": \"‸\" }")
        val request = FeatureRequest(document, ExampleProject.analysis, null, DescriptorFixture.treeWith(eventsFile))

        val labels = features.completion.complete(request, offset).sortedWith(compareBy({ it.sortKey }, { it.label })).map { it.label }

        assertEquals("EVENTS.kmeta", labels.first())
        assertTrue("robot_events.kmeta" in labels, "$labels")
        val usualElsewhere = labels.indexOf("../delivery_drone_example/EVENTS.kmeta")
        assertTrue(labels.indexOf("robot_events.kmeta") > usualElsewhere, "usual files first: $labels")
    }

    @Test
    fun `attachedTo offers the model's objects, those of the state machine's ATTACHED TO type first`() {
        val text = valid.replace("\"attachedTo\": \"turtle\"", "\"attachedTo\": \"‸\"")

        val items = complete(text)

        assertEquals(listOf("turtle"), firstRanked(items), "the only Robot of the model")
        assertTrue(listOf("APB 2101", "chair", "wall_top", "turtlePosition").all { it in items.map(CompletionItem::label) })
        assertEquals("Robot", items.first().detail)
        assertEquals(CompletionKind.OBJECT, items.first().kind)
        assertEquals("turtle", items.first().insertText)
    }

    @Test
    fun `observed domains are the event domains of the descriptor's state machines`() {
        val text = valid.replace("\"domains\": [\"public\"]", "\"domains\": [\"‸\"]")

        val items = complete(text)

        assertEquals(listOf("public"), items.map { it.label })
        assertEquals(CompletionKind.EVENT, items.single().kind)
        assertEquals(emptyList(), complete("{ \"observe\": { \"domains\": [ \"‸\" ] } }").map { it.label }, "no state machine, no domains")
    }

    @Test
    fun `numbers, patterns and object templates have typical values`() {
        assertEquals(listOf("50", "100", "500"), complete("{ \"tickDelayMs\": ‸ }").map { it.label })
        assertEquals(CompletionKind.VALUE, complete("{ \"tickDelayMs\": ‸ }").first().kind)
        assertEquals(emptyList(), complete("{ \"tickDelayMs\": \"‸\" }"), "no numbers inside a string")
        assertEquals(listOf("*"), complete("{ \"observe\": { \"objects\": \"‸\" } }").map { it.label })
        val template = complete("{ \"observe\": ‸ }").single()
        assertEquals("{ … }" to "{ \"objects\": \"\" }", template.label to template.insertText)
        assertEquals("{ \"objects\": \"".length, template.caretOffsetInInsert)
    }

    @Test
    fun `a number, a literal or the start of a literal typed as a value gets no proposal that would replace it`() {
        assertEquals(emptyList(), complete("{ \"tickDelayMs\": 10‸ }").map { it.label }, "100 would replace 10 on Enter")
        assertEquals(emptyList(), complete("{ \"eventTtlMs\": 30‸ }").map { it.label })
        assertEquals(emptyList(), complete("{ \"events\": nu‸ }").map { it.label }, "on the way to null")
        assertEquals(emptyList(), complete("{ \"events\": null‸ }").map { it.label })
        assertEquals(emptyList(), complete("{ \"bridges\": [ fa‸ ] }").map { it.label })

        assertEquals(listOf("50", "100", "500"), complete("{ \"tickDelayMs\": ‸ }").map { it.label }, "nothing typed yet")
        assertEquals("cleaning_robot.kmodel", complete("{ \"model\": cl‸ }").first().label, "a word that begins no literal")
    }

    @Test
    fun `hover explains keys, resolves file paths and names the attached object's type`() {
        val document = DescriptorFixture.document(valid)

        val key = assertNotNull(services.hover(document, after(valid, "\"meta")))
        assertEquals(TextRange.ofLength(valid.indexOf("metamodel"), "metamodel".length), key.range)
        assertTrue(key.text.startsWith("metamodel: a file path, required\n"), key.text)

        val file = assertNotNull(services.hover(document, after(valid, "\"cleaning_robot.km")))
        assertEquals("The domain metamodel: ${robotDir.resolve("cleaning_robot.kmeta")}", file.text)

        assertEquals("object turtle: Robot", services.hover(document, after(valid, "\"tur"))?.text)
        assertTrue(services.hover(document, after(valid, "5"))!!.text.startsWith("tickDelayMs: a whole number, optional"))
        assertEquals("event domain public", services.hover(document, after(valid, "\"pub"))?.text)
    }

    @Test
    fun `hover reports unknown keys, missing files and unknown objects`() {
        val text = valid.replace("\"tickDelayMs\"", "\"tickDelay\"").replace("cleaning_robot.kmodel", "missing.kmodel")
        val document = DescriptorFixture.document(text)

        assertTrue(services.hover(document, after(text, "\"tickD"))!!.text.startsWith("Unknown key 'tickDelay'"))
        assertTrue(services.hover(document, after(text, "\"missing"))!!.text.endsWith("which is not in the workspace"))
        assertEquals("The model missing.kmodel has no object 'turtle'.", services.hover(document, after(text, "\"tur"))?.text)
    }

    @Test
    fun `a named file the analysis knows but the tree lacks is missing, as the descriptor's finding says`() {
        val metamodel = robotDir.resolve("cleaning_robot.kmeta")
        val treeless = DescriptorFixture.services(failures, DescriptorFixture.treeWithout(metamodel))
        val document = DescriptorFixture.document(valid)
        val onPath = after(valid, "\"cleaning_robot.km")
        assertNotNull(ExampleProject.analysis.file(metamodel), "the analysis knows the file")

        assertEquals("The domain metamodel: $metamodel, which is not in the workspace", treeless.hover(document, onPath)?.text)
        assertEquals(emptyList(), treeless.definition(document, onPath))
        val (empty, offset) = DescriptorFixture.at("{ \"metamodel\": \"‸\" }")
        assertTrue("cleaning_robot.kmeta" !in treeless.complete(empty, offset).map { it.label })
    }

    @Test
    fun `a named file of another language than its role is there, and hover says what it is not`() {
        val text = valid.replace("\"metamodel\": \"cleaning_robot.kmeta\"", "\"metamodel\": \"cleaning_robot.kstates\"")
        val document = DescriptorFixture.document(text)
        val onPath = after(text, "\"metamodel\": \"cleaning_robot.ks")
        val machine = ExampleProject.pathOf(ExamplePaths.ROBOT_KSTATES)

        assertEquals("The domain metamodel: $machine, which is not a KMeta file", services.hover(document, onPath)?.text)
        assertEquals(listOf(machine), services.definition(document, onPath).map { it.path })
    }

    @Test
    fun `go to definition opens the named file and finds the attached object in the model`() {
        val document = DescriptorFixture.document(valid)
        val modelPath = ExampleProject.pathOf(ExamplePaths.ROBOT_KMODEL)

        val machine = services.definition(document, after(valid, "\"cleaning_robot.ks")).single()
        assertEquals(ExampleProject.pathOf(ExamplePaths.ROBOT_KSTATES) to TextRange.EMPTY, machine.path to machine.range)
        assertEquals(0L, machine.version)

        val turtle = services.definition(document, after(valid, "\"tur")).single()
        assertEquals(modelPath, turtle.path)
        val modelText = ExampleProject.textOf(ExamplePaths.ROBOT_KMODEL)
        assertEquals("turtle", modelText.substring(turtle.range.start, turtle.range.end))
        val declaration = ExampleProject.analysis.file(modelPath)!!.parse.index.declarations(SymbolKind.OBJECT, "turtle").single()
        assertEquals(declaration.nameRange, turtle.range)

        assertEquals(emptyList(), services.definition(document, after(valid, "\"meta")), "keys lead nowhere")
        val missing = valid.replace("cleaning_robot.kmodel", "missing.kmodel")
        assertEquals(emptyList(), services.definition(DescriptorFixture.document(missing), after(missing, "\"missing")))
    }

    @Test
    fun `a quote, digit or literal typed before the opening brace keeps completion, hover and brackets working`() {
        listOf("\"", "1", "true\n").forEach { stray ->
            assertEquals(listOf("cleaning_robot.kmodel"), firstRanked(complete(stray + "{\n  \"model\": \"‸\"\n}")), "'$stray'")
            val text = stray + valid
            val hover = services.hover(DescriptorFixture.document(text), after(text, "\"meta"))
            assertTrue(hover?.text.orEmpty().startsWith("metamodel: a file path, required"), "'$stray': $hover")
        }
        val quoted = "\"" + valid
        val list = assertNotNull(services.matchingBracket(DescriptorFixture.document(quoted), quoted.indexOf('[')))
        assertEquals(quoted.indexOf(']'), list.close.start)
        assertNull(services.matchingBracket(DescriptorFixture.document(quoted), quoted.lastIndexOf('}')), "its '{' is part of a string")
    }

    @Test
    fun `brackets pair as the parser closed them`() {
        val document = DescriptorFixture.document(valid)

        val outer = assertNotNull(services.matchingBracket(document, 0))
        assertEquals(TextRange(0, 1) to TextRange(valid.lastIndexOf('}'), valid.lastIndexOf('}') + 1), outer.open to outer.close)
        val list = assertNotNull(services.matchingBracket(document, valid.indexOf('[')))
        assertEquals(valid.indexOf(']'), list.close.start)
        assertNull(services.matchingBracket(DescriptorFixture.document("{\"a\": [1"), 6), "an open list has no match")
    }

    @Test
    fun `file paths and object keys are highlighted by their role, numbers by their token`() {
        val document = DescriptorFixture.document(valid, version = 1)

        val spans = services.highlight(document).associate { valid.substring(it.range.start, it.range.end) to it.style }

        assertEquals(HighlightStyle.FILE_PATH, spans["cleaning_robot.kmeta"])
        assertEquals(HighlightStyle.OBJECT_NAME, spans["turtle"])
        assertEquals(HighlightStyle.PROPERTY_NAME, spans["metamodel"])
        assertEquals(HighlightStyle.NUMBER, spans["3000"])
        assertEquals(HighlightStyle.EVENT_DOMAIN, spans["public"])
        assertEquals(HighlightStyle.PUNCTUATION, spans["{"])
    }
}
