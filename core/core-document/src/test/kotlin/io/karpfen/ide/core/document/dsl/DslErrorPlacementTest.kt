package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.antlr.tree.KmetaSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KmodelSyntaxAnalyzer
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real dsl-tools errors land on the offending key, not on an earlier, valid symbol that shares a name the message
 * quotes (a type or a property name used by several objects or types).
 */
class DslErrorPlacementTest {

    private val converter: DslConverter = DslConverter()
    private val locator: DslErrorLocator = DslErrorLocator()

    private val metamodel: Metamodel by lazy {
        val source = TextSnapshot(Examples.file("karpfen-dsl-tools/metamodel_dsl_example.kmeta").text, 0)
        converter.kmeta(source, KmetaSyntaxAnalyzer().analyze(source).index).value!!
    }

    private val model = Examples.file("karpfen-dsl-tools/model_dsl_example.kmodel").text

    /** [text] with the first [old] after [anchor] replaced by [new]. */
    private fun replaceAfter(text: String, anchor: String, old: String, new: String): String {
        val at = text.indexOf(old, text.indexOf(anchor))
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun kmodelError(text: String): TextRange {
        val source = TextSnapshot(text, 0)
        val conversion = converter.kmodel(source, KmodelSyntaxAnalyzer().analyze(source).index, metamodel)
        return conversion.diagnostics.single().range
    }

    private fun kmetaError(text: String): TextRange {
        val source = TextSnapshot(text, 0)
        return converter.kmeta(source, KmetaSyntaxAnalyzer().analyze(source).index).diagnostics.single().range
    }

    private fun lineOf(text: String, range: TextRange): Int = text.substring(0, range.start).count { it == '\n' } + 1

    @Test
    fun `an unknown property is located at its key, not at the first object of the same type`() {
        val text = replaceAfter(model, "\"bottomRightPoint\"", "prop(\"x\")", "prop(\"z\")")

        val range = kmodelError(text)

        assertEquals(TextRange.ofLength(text.indexOf("prop(\"z\")") + 6, 1), range)
        assertEquals(15, lineOf(text, range))
    }

    @Test
    fun `an unknown relation is located at its key, not at the enclosing object's name`() {
        val text = model.replace("has(\"bottom_right\")", "has(\"bottom_rite\")")

        val range = kmodelError(text)

        assertEquals(TextRange.ofLength(text.indexOf("bottom_rite"), "bottom_rite".length), range)
        assertEquals(14, lineOf(text, range))
    }

    @Test
    fun `a property with an unknown value type is located in the type that declares it wrongly`() {
        val text = "type \"Point\" \"p\" {\n    prop(\"x\", \"number\")\n}\n\ntype \"Size\" \"s\" {\n    prop(\"x\", \"nmber\")\n}"

        val range = kmetaError(text)

        assertEquals(TextRange.ofLength(text.lastIndexOf("\"x\"") + 1, 1), range)
        assertEquals(6, lineOf(text, range))
    }

    @Test
    fun `a key of the named object wins over the same key of an object nested in it`() {
        val text = "make object \"outer\":\"Box\" {\n" +
            "    has(\"inner\") -> make object \"child\":\"Box\" {\n        prop(\"z\") -> \"1\"\n    }\n" +
            "    prop(\"z\") -> \"2\"\n}"
        val source = TextSnapshot(text, 0)
        val index = KmodelSyntaxAnalyzer().analyze(source).index

        val range = locator.locate("Unknown property 'z' for class type 'Box' in object 'outer'", source, index)

        assertEquals(TextRange.ofLength(text.lastIndexOf("\"z\"") + 1, 1), range)
    }

    @Test
    fun `a key of the type the message names wins over the same key of another type`() {
        val text = "make object \"outer\":\"Box\" {\n" +
            "    has(\"inner\") -> make object \"child\":\"Point\" {\n        prop(\"z\") -> \"1\"\n    }\n" +
            "    prop(\"z\") -> \"2\"\n}"
        val source = TextSnapshot(text, 0)
        val index = KmodelSyntaxAnalyzer().analyze(source).index

        val range = locator.locate("Unknown property 'z' for class type 'Box'", source, index)

        assertEquals(TextRange.ofLength(text.lastIndexOf("\"z\"") + 1, 1), range)
    }
}
