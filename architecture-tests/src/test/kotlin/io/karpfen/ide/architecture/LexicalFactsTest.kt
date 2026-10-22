package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One source per fact: the lexical facts of the karpfen languages that the tokenizers, the language services and the
 * editor must agree on are declared only in core-document's `KarpfenSyntax`, the member keywords only in core-lang's
 * `MemberKind` (declared in `TypeCatalog`). `KarpfenSyntax` holds the identifier characters, the primitive types, the
 * KStates operations with the subsets that take a relation or no value, the block keywords that open action and
 * structural blocks, and the model-path syntax (the separator and the blanks a path may hold). A second declaration
 * could drift unnoticed, for example the completion's replace range against the editor's word rule, or a path the
 * walker names differently than the resolver reads it.
 *
 * Each fact is recognized by the shape of its declarations in production code. Some facts have several shapes: the
 * KStates operations count as a set or written out as completion snippets (`"SET(\"…`); the block keywords count
 * quoted or as lexer tokens; the model-path separator counts as a constant or as a character-by-character scan.
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
        /** The home of most facts: core-document's `KarpfenSyntax`. */
        const val SYNTAX = "KarpfenSyntax"

        /** The facts by name; the class documentation lists the shapes that count as a declaration. */
        val FACTS: Map<String, Fact> = mapOf(
            "identifier characters" to Fact(Regex("""in 'a'\.\.'z' \|\| \w+ in 'A'\.\.'Z'""")),
            "primitive types" to Fact(Regex("""setOf\("number", "string", "boolean"\)""")),
            "KStates operations" to Fact(Regex("""setOf\("SET", "APPEND"|"(SET|APPEND|SETLIST|DROPLIST|SETOBJ|APPENDOBJ|DROPOBJ)\(\\"""")),
            "KStates relation operations" to Fact(Regex("""setOf\("SETOBJ", "APPENDOBJ"\)""")),
            "KStates unary operations" to Fact(Regex("""setOf\("DROPLIST", "DROPOBJ"\)""")),
            "KStates action blocks" to Fact(Regex("""(KstatesLexer\.|")ENTRY"?,\s*(KstatesLexer\.|")DO"?,\s*(KstatesLexer\.|")IF\b""")),
            "KStates structure blocks" to Fact(Regex("""(KstatesLexer\.|")STATEMACHINE"?,\s*(KstatesLexer\.|")STATES\b""")),
            "model-path separator" to Fact(Regex("""SEPARATOR\w*(: String)? = "->"|'-'[^\n]{0,40}'>'""")),
            "model-path blanks" to Fact(Regex("""(fun \w*[Bb]lank\w*\(\w+: Char\)|filterNot \{)[^\n]*== ' ' \|\| \w+ == '\\t'""")),
            "member keywords" to Fact(Regex("""PROPERTY\("prop"\)|(\bPROP|"prop") to MemberKind\."""), home = "TypeCatalog"),
        )
    }
}
