package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One source per fact (decision 46 of the work-package plan): the lexical facts of the karpfen languages that the
 * tokenizers, the language services and the editor must agree on are declared only in core-document's `KarpfenSyntax`.
 * A second declaration could drift unnoticed (e.g. the completion's replace range against the editor's word rule).
 */
class LexicalFactsTest {

    @Test
    fun `the identifier rule, the primitive types, the KStates operations and block keywords are declared only in KarpfenSyntax`() {
        val declarations = FACTS.mapValues { (_, pattern) ->
            KarpfenModules.mainFiles.filter { pattern.containsMatchIn(it.text) }.map { it.name }.sorted()
        }

        assertEquals(FACTS.mapValues { listOf(HOME) }, declarations)
    }

    private companion object {
        const val HOME = "KarpfenSyntax"

        /** Each fact by the shape of its declaration. */
        val FACTS: Map<String, Regex> = mapOf(
            "identifier characters" to Regex("""in 'a'\.\.'z' \|\| \w+ in 'A'\.\.'Z'"""),
            "primitive types" to Regex("""setOf\("number", "string", "boolean"\)"""),
            "KStates operations" to Regex("""setOf\("SET", "APPEND""""),
            "KStates relation operations" to Regex("""setOf\("SETOBJ", "APPENDOBJ"\)"""),
            // The block rule (decision 60): which keywords open action blocks and structural blocks, quoted or as tokens.
            "KStates action blocks" to Regex("""(KstatesLexer\.|")ENTRY"?,\s*(KstatesLexer\.|")DO"?,\s*(KstatesLexer\.|")IF\b"""),
            "KStates structure blocks" to Regex("""(KstatesLexer\.|")STATEMACHINE"?,\s*(KstatesLexer\.|")STATES\b"""),
        )
    }
}
