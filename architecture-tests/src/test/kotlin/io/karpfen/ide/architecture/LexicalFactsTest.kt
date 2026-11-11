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
package io.karpfen.ide.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each lexical fact of the karpfen languages has one source. The tokenizers, the language services and the editor must
 * agree on these facts, so they are declared only in core-document's `KarpfenSyntax`. The member keywords are the one
 * exception. They are declared only in core-lang's `MemberKind`, which lives in the file `TypeCatalog`.
 *
 * `KarpfenSyntax` holds the identifier characters, the primitive types, the KStates operations with the subsets that
 * take a relation or no value, the block keywords that open action and structural blocks, the model-path syntax with
 * its separator and the blanks a path may hold, and the line comment markers of the modeling languages and of the
 * Python in EVAL bodies. A second declaration could drift unnoticed. The replace range of the completion could drift
 * from the word rule of the editor, for example, or the walker could name a path differently from how the resolver
 * reads it.
 *
 * Each fact is recognized by the shape of its declarations in production code. Some facts have several shapes:
 *
 * - The primitive types count when written out as a set or taken from the keywords of dsl-tools' `SimplePropertyType`.
 * - The KStates operations count as a set, when taken from dsl-tools' `ActionOperationType`, or when written out as
 *   completion snippets (`"SET(\"…`).
 * - The operations that take a relation or no value count as a set or when chosen by their operand count.
 * - The block keywords count when quoted or as lexer tokens.
 * - The model-path separator counts as a constant or as a scan character by character.
 * - A comment marker counts as its string literal.
 */
class LexicalFactsTest {

    @Test
    fun `every lexical fact is declared only in its home`() {
        val declarations = FACTS.mapValues { (_, fact) ->
            KarpfenModules.mainFiles.filter { fact.pattern.containsMatchIn(it.text) }.map { it.name }.sorted()
        }

        assertEquals(FACTS.mapValues { listOf(it.value.home) }, declarations)
    }

    /**
     * A fact recognized by the shape of its declaration, and the one file that may declare it.
     *
     * @property pattern the shapes of a declaration of the fact in production code.
     * @property home the name of the one production file that may declare the fact, without its extension.
     */
    private class Fact(val pattern: Regex, val home: String = SYNTAX)

    private companion object {
        /** The home of most facts: core-document's `KarpfenSyntax`. */
        const val SYNTAX = "KarpfenSyntax"

        /** The facts by name. The class documentation lists the shapes that count as a declaration. */
        val FACTS: Map<String, Fact> = mapOf(
            "identifier characters" to Fact(Regex("""in 'a'\.\.'z' \|\| \w+ in 'A'\.\.'Z'""")),
            "primitive types" to Fact(Regex("""setOf\("number", "string", "boolean"\)|SimplePropertyType\.entries\.\w+""")),
            "KStates operations" to Fact(
                Regex("""setOf\("SET", "APPEND"|ActionOperationType\.entries|"(SET|APPEND|SETLIST|DROPLIST|SETOBJ|APPENDOBJ|DROPOBJ)\(\\""""),
            ),
            "KStates relation operations" to Fact(Regex("""setOf\("SETOBJ", "APPENDOBJ"\)|operandCount == 3""")),
            "KStates unary operations" to Fact(Regex("""setOf\("DROPLIST", "DROPOBJ"\)|operandCount == 1""")),
            "KStates action blocks" to Fact(Regex("""(KstatesLexer\.|")ENTRY"?,\s*(KstatesLexer\.|")DO"?,\s*(KstatesLexer\.|")IF\b""")),
            "KStates structure blocks" to Fact(Regex("""(KstatesLexer\.|")STATEMACHINE"?,\s*(KstatesLexer\.|")STATES\b""")),
            "model-path separator" to Fact(Regex("""SEPARATOR\w*(: String)? = "->"|'-'[^\n]{0,40}'>'""")),
            "model-path blanks" to Fact(Regex("""(fun \w*[Bb]lank\w*\(\w+: Char\)|filterNot \{)[^\n]*== ' ' \|\| \w+ == '\\t'""")),
            "karpfen line comment" to Fact(Regex(""""//"""")),
            "Python line comment" to Fact(Regex(""""#"""")),
            "member keywords" to Fact(Regex("""PROPERTY\("prop"\)|(\bPROP|"prop") to MemberKind\."""), home = "TypeCatalog"),
        )
    }
}
