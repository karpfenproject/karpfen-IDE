package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kstates.KstatesParser
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.DslGrammar
import io.karpfen.ide.core.document.antlr.EmbeddedKind
import io.karpfen.ide.core.document.antlr.EvalBodies
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers that EVAL references and folding come from the same lexical scanner the tokenizer uses: every MODEL_PATH or
 * MACRO reference inside an EVAL body is exactly the inner range (and name) of a PATH_REFERENCE or MACRO_REFERENCE
 * piece of `EvalBodies.scan` over the tokenizer's lexing (`KarpfenLexers.lex`), and every such piece is a reference, on
 * the examples, a synthetic document and mutations alike.
 */
class EmbeddedCodeTest {

    private val machines = Examples.files(KarpfenLanguage.KSTATES).map { it.text } + SyntheticKstates(40)

    @Test
    fun `EVAL references are the tokenizer's reference pieces`() {
        machines.forEach(::checkAgainstTokenizer)
    }

    @Test
    fun `EVAL references agree with the tokenizer on mutations`() {
        machines.forEachIndexed { index, text ->
            Mutations.random(text, seed = 77L + index, count = 400).forEach { mutation ->
                checkAgainstTokenizer(mutation.text)
            }
        }
    }

    @Test
    fun `multi-line bodies fold as embedded code, single-line and unclosed ones do not`() {
        val text = "SET(\"a\", EVAL {\n  return 1\n})\nSET(\"b\", EVAL { return 2 })\nSET(\"c\", EVAL {\n  x = 1\n"

        val folding = analyze(KarpfenLanguage.KSTATES, text).folding.filter { it.kind == FoldingKind.EMBEDDED_CODE }

        assertEquals(listOf(TextRange(text.indexOf('{'), text.indexOf('}') + 1)), folding.map { it.range })
    }

    @Test
    fun `Python braces inside a body never fold as blocks`() {
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES { }\n TRANSITIONS {\n  TRANSITION \"a\" -> \"b\" {\n   CONDITION {\n" +
            "    EVAL {\n     d = {\n      1: 2\n     }\n     return d\n    }\n   }\n  }\n }\n}"

        val folding = analyze(KarpfenLanguage.KSTATES, text).folding
        val body = folding.single { it.kind == FoldingKind.EMBEDDED_CODE }.range

        assertTrue(folding.filter { it.kind == FoldingKind.BLOCK }.none { it.range.start > body.start && it.range.start < body.end })
        assertEquals(4, folding.count { it.kind == FoldingKind.BLOCK }, "machine, TRANSITIONS, TRANSITION, CONDITION")
    }

    @Test
    fun `what error recovery parses inside a body is not karpfen`() {
        val text = "STATEMACHINE ATTACHED TO \"T\" {\n STATES { }\n TRANSITIONS { }\n MACROS {\n  MACRO \"m\" {\n" +
            "   TAKES(\"a\", \"Vector\")\nEVAL {   TAKES(\"b\", \"Vector\")\n   RETURNS(\"number\")\n" +
            "   DEFINITION { EVAL { return 1 } }\n  }\n }\n}"
        val tree = TreeParser(DslGrammar.KSTATES, ::KstatesParser, KstatesParser::kstates_file).parse(TextSnapshot(text, 0))
        val unguarded = SyntaxCollector().also { KstatesTreeWalker(tree, it).walk(tree.root) }.index()
        assertEquals(listOf("a", "b"), unguarded.declarationsOf(SymbolKind.MACRO_PARAMETER).map { it.name }, "premise: recovery parses `b`")

        val analysis = analyze(KarpfenLanguage.KSTATES, text)

        assertTrue(analysis.syntaxDiagnostics.isNotEmpty())
        assertEquals(listOf("a"), analysis.index.declarationsOf(SymbolKind.MACRO_PARAMETER).map { it.name }, "`b` is Python text")
        assertEquals(1, analysis.index.references(ReferenceKind.TYPE, "Vector").size)
        assertTrue(analysis.semanticSpans.none { it.range.start > text.indexOf("EVAL {") && text.at(it.range) == "b" })
    }

    private fun checkAgainstTokenizer(text: String) {
        val lexed = KarpfenLexers.lex(DslGrammar.KSTATES, text)
        val scanned = EvalBodies.scanAll(text, lexed.tokens, lexed.offsets)
        val pieces = scanned.flatMap { it.second }.mapNotNull { piece ->
            when (piece.kind) {
                EmbeddedKind.PATH_REFERENCE -> Triple(ReferenceKind.MODEL_PATH, piece.name, piece.inner)
                EmbeddedKind.MACRO_REFERENCE -> Triple(ReferenceKind.MACRO, piece.name, piece.inner)
                else -> null
            }
        }
        val bodies = scanned.map { it.first.content }
        val analysis = analyze(KarpfenLanguage.KSTATES, text)
        val embedded = analysis.index.references
            .filter { it.kind == ReferenceKind.MODEL_PATH || it.kind == ReferenceKind.MACRO }
            .filter { reference -> bodies.any { reference.range in it } }
            .map { Triple(it.kind, it.name, it.range) }
        assertEquals(pieces, embedded, "EVAL references of\n$text")
        assertEquals(
            pieces.filter { !it.third!!.isEmpty }.map { it.third },
            analysis.semanticSpans.filter { span -> bodies.any { span.range in it } }.map { it.range },
            "EVAL spans of\n$text",
        )
        assertTrue(analysis.index.declarations.none { declaration -> bodies.any { declaration.nameRange in it } })
    }
}
