package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.meta.Metamodel
import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * dsl-tools findings land on the offending element, where the reader reports them, also in the broken inputs on which
 * the search for quoted names that the IDE used before the readers chose the wrong place: a name that appears earlier
 * in the file, an unresolved `knows` reference, an unknown property in a nested object, a second `INITIAL` state, and
 * findings whose message quotes no name of the file at all.
 */
class DiagnosticPlacementTest {

    private val reader: DslReader = DslReader()

    private val metamodel: Metamodel by lazy {
        requireNotNull(reader.kmeta(TextSnapshot(Examples.file("karpfen-dsl-tools/metamodel_dsl_example.kmeta").text, 0)).model)
    }

    private val model = Examples.file("karpfen-dsl-tools/model_dsl_example.kmodel").text

    /** [text] with the first [old] after the first [anchor] replaced by [new]. */
    private fun replaceAfter(text: String, anchor: String, old: String, new: String): String {
        val at = text.indexOf(old, text.indexOf(anchor).also { require(it >= 0) { "no '$anchor'" } })
        require(at >= 0) { "no '$old' after '$anchor'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    /** The range of [name] between the quotes of the first `"name"` at or after the first [anchor] of [text]. */
    private fun quotedAfter(text: String, anchor: String, name: String): TextRange {
        val at = text.indexOf("\"$name\"", text.indexOf(anchor))
        require(at >= 0) { "no \"$name\" after '$anchor'" }
        return TextRange.ofLength(at + 1, name.length)
    }

    private fun kmodelFindings(text: String): List<Diagnostic> = reader.kmodel(TextSnapshot(text, 0), metamodel).diagnostics

    private fun lineOf(text: String, range: TextRange): Int = text.substring(0, range.start).count { it == '\n' } + 1

    @Test
    fun `an unknown target type is marked where it is used, not at an earlier property of the same name`() {
        val text = "type \"Point\" \"p\" {\n    prop(\"Vector\", \"string\")\n}\n\n" +
            "type \"Robot\" \"r\" {\n    has(\"position\", \"Vector\")\n}\n"

        val finding = reader.kmeta(TextSnapshot(text, 0)).diagnostics.single()

        assertEquals("kmeta.unknown-target-type", finding.code)
        assertEquals(quotedAfter(text, "has(", "Vector"), finding.range)
        assertEquals(6, lineOf(text, finding.range))
    }

    @Test
    fun `an unresolved knows reference is marked at the id it names, not at the key of the same name`() {
        val text = model.replace("knows(\"obstacles\") -> \"chair\"", "knows(\"obstacles\") -> \"obstacles\"")

        val finding = kmodelFindings(text).single()

        assertEquals("kmodel.unresolved-reference", finding.code)
        assertEquals(quotedAfter(text, "-> \"obstacles\"", "obstacles"), finding.range)
    }

    @Test
    fun `an unknown property in a nested object is marked at its own key, not at an earlier relation of that name`() {
        val anchor = "make object \"tablePosition\""
        val text = replaceAfter(model, anchor, "            }\n", "            }\n            prop(\"position\") -> \"1\"\n")

        val finding = kmodelFindings(text).single()

        assertEquals("kmodel.unknown-property", finding.code)
        assertEquals(quotedAfter(text, "prop(\"position\")", "position"), finding.range)
        assertEquals(34, lineOf(text, finding.range))
    }

    @Test
    fun `a second INITIAL state is marked at its name, and so is the first`() {
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES {\n  INITIAL STATE \"a\" { }\n  INITIAL STATE \"b\" { }\n }\n" +
            " TRANSITIONS { }\n}"

        val findings = reader.kstates(TextSnapshot(text, 0)).diagnostics

        assertEquals(listOf("kstates.initial-state-count"), findings.map { it.code }.distinct())
        assertEquals(listOf(quotedAfter(text, "STATE", "a"), quotedAfter(text, "STATE", "b")), findings.map { it.range })
    }

    @Test
    fun `a value that is no number is marked at the value in its nested object`() {
        val text = replaceAfter(model, "make object \"tablePosition\"", "prop(\"x\") -> \"5.0\"", "prop(\"x\") -> \"five\"")

        val finding = kmodelFindings(text).single()

        assertEquals("kmodel.invalid-number", finding.code)
        assertEquals(quotedAfter(text, "make object \"tablePosition\"", "five"), finding.range)
    }

    @Test
    fun `a reference to an object of another type is marked at the id it names`() {
        val text = model.replace("knows(\"obstacles\") -> \"table\"", "knows(\"obstacles\") -> \"tableBoundingBox\"")

        val finding = kmodelFindings(text).single()

        assertEquals("kmodel.reference-type-mismatch", finding.code)
        assertEquals(quotedAfter(text, "knows(\"obstacles\") -> ", "tableBoundingBox"), finding.range)
    }

    @Test
    fun `every error of a file is reported, each at its own place`() {
        val withTypo = replaceAfter(model, "make object \"chairPosition\"", "prop(\"y\")", "prop(\"z\")")
        val text = withTypo.replace("knows(\"obstacles\") -> \"table\"", "knows(\"obstacles\") -> \"sofa\"")

        val findings = kmodelFindings(text)

        assertEquals(listOf("kmodel.unknown-property", "kmodel.unresolved-reference"), findings.map { it.code })
        assertEquals(listOf("z", "sofa"), findings.map { text.substring(it.range.start, it.range.end) })
    }
}
