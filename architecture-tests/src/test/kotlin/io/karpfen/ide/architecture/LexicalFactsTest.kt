package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One source per fact (decision 46 of the work-package plan): the lexical facts of the karpfen languages that the
 * tokenizers, the language services and the editor must agree on are declared only in core-document's `KarpfenSyntax`
 * (decisions 53, 60 and 61), the member keywords only in core-lang's `MemberKind`. A second declaration could drift
 * unnoticed (e.g. the completion's replace range against the editor's word rule, or a path the walker names
 * differently than the resolver reads it).
 */
class LexicalFactsTest {

    @Test
    fun `every lexical fact is declared only in its home`() {
        val declarations = FACTS.mapValues { (_, fact) ->
            KarpfenModules.mainFiles.filter { fact.pattern.containsMatchIn(it.text) }.map { it.name }.sorted()
        }

        assertEquals(FACTS.mapValues { listOf(it.value.home) }, declarations)
    }

    /** A fact recognized by the shape of its declaration, and the one file that may declare it. */
    private class Fact(val pattern: Regex, val home: String = SYNTAX)

    private companion object {
        const val SYNTAX = "KarpfenSyntax"

        val FACTS: Map<String, Fact> = mapOf(
            "identifier characters" to Fact(Regex("""in 'a'\.\.'z' \|\| \w+ in 'A'\.\.'Z'""")),
            "primitive types" to Fact(Regex("""setOf\("number", "string", "boolean"\)""")),
            // The operations as a set, or written out as completion snippets (`"SET(\"…`).
            "KStates operations" to Fact(Regex("""setOf\("SET", "APPEND"|"(SET|APPEND|SETLIST|DROPLIST|SETOBJ|APPENDOBJ|DROPOBJ)\(\\"""")),
            "KStates relation operations" to Fact(Regex("""setOf\("SETOBJ", "APPENDOBJ"\)""")),
            "KStates unary operations" to Fact(Regex("""setOf\("DROPLIST", "DROPOBJ"\)""")),
            // The block rule: which keywords open action blocks and structural blocks, quoted or as tokens.
            "KStates action blocks" to Fact(Regex("""(KstatesLexer\.|")ENTRY"?,\s*(KstatesLexer\.|")DO"?,\s*(KstatesLexer\.|")IF\b""")),
            "KStates structure blocks" to Fact(Regex("""(KstatesLexer\.|")STATEMACHINE"?,\s*(KstatesLexer\.|")STATES\b""")),
            // Model paths: the separator as a constant or scanned character by character, and the blanks they may hold.
            "model-path separator" to Fact(Regex("""SEPARATOR\w*(: String)? = "->"|'-'[^\n]{0,40}'>'""")),
            "model-path blanks" to Fact(Regex("""(fun \w*[Bb]lank\w*\(\w+: Char\)|filterNot \{)[^\n]*== ' ' \|\| \w+ == '\\t'""")),
            "member keywords" to Fact(Regex("""PROPERTY\("prop"\)|(\bPROP|"prop") to MemberKind\."""), home = "TypeCatalog"),
        )
    }
}
