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
package io.karpfen.ide.core.document

import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SymbolReference
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.document.parse.pathAt
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.VersionedDiagnostics
import io.karpfen.ide.core.document.session.mergeDiagnostics
import io.karpfen.ide.core.document.syntax.SyntaxToken
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.document.syntax.Tokenizer
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Contract test of the core-document values and wiring: languages by file name, diagnostic order, token and embedded
 * region lookups, the one merge rule, the syntax index, outline paths, registries and document ids. It never tokenizes
 * or parses.
 */
class DocumentContractTest {

    @Test
    fun `languages are recognized by extension ignoring case`() {
        assertEquals(KarpfenLanguage.KMETA, KarpfenLanguage.fromFileName("EVENTS.kmeta"))
        assertEquals(KarpfenLanguage.KSTATES, KarpfenLanguage.fromFileName("Robot.KSTATES"))
        assertEquals(KarpfenLanguage.ENVIRONMENT, KarpfenLanguage.fromFileName("descriptor.KENV.json"))
        assertNull(KarpfenLanguage.fromFileName("descriptor.json"))
        assertNull(KarpfenLanguage.fromFileName("kenv.json"))
        assertNull(KarpfenLanguage.fromFileName("kmeta"))
        assertNull(KarpfenLanguage.fromFileName("file."))
    }

    @Test
    fun `diagnostics sort in document order, most severe first`() {
        fun diagnostic(start: Int, severity: Severity) =
            Diagnostic(TextRange(start, start + 1), severity, "m", DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)

        val sorted = listOf(diagnostic(5, Severity.INFO), diagnostic(1, Severity.WARNING), diagnostic(1, Severity.ERROR))
            .sortedWith(Diagnostic.ORDER)

        assertEquals(listOf(Severity.ERROR, Severity.WARNING, Severity.INFO), sorted.map { it.severity })
    }

    @Test
    fun `token lookups use binary search over sorted, non-overlapping tokens`() {
        val tokens = TokenizeResult(
            version = 0,
            tokens = listOf(
                SyntaxToken(TextRange(0, 4), TokenKind.KEYWORD),
                SyntaxToken(TextRange(5, 13), TokenKind.STRING),
                SyntaxToken(TextRange(14, 15), TokenKind.PUNCTUATION),
            ),
            diagnostics = emptyList(),
        )

        assertEquals(TokenKind.STRING, tokens.tokenAt(5)?.kind)
        assertNull(tokens.tokenAt(4))
        assertEquals(TokenKind.KEYWORD, tokens.tokenBefore(4)?.kind)
        assertEquals(listOf(TokenKind.KEYWORD, TokenKind.STRING), tokens.tokensIn(TextRange(2, 6)).map { it.kind })
        assertFailsWith<IllegalArgumentException> {
            TokenizeResult(0, listOf(SyntaxToken(TextRange(0, 4), TokenKind.KEYWORD), SyntaxToken(TextRange(3, 5), TokenKind.STRING)), emptyList())
        }
        assertFailsWith<IllegalArgumentException> { SyntaxToken(TextRange.empty(2), TokenKind.ERROR) }
    }

    @Test
    fun `embedded regions are found with inclusive bounds`() {
        val tokens = TokenizeResult(0, emptyList(), emptyList(), embeddedRegions = listOf(TextRange(10, 20), TextRange(30, 30)))

        assertEquals(TextRange(10, 20), tokens.embeddedRegionAt(10))
        assertEquals(TextRange(10, 20), tokens.embeddedRegionAt(20))
        assertEquals(TextRange(30, 30), tokens.embeddedRegionAt(30))
        assertNull(tokens.embeddedRegionAt(9))
        assertNull(tokens.embeddedRegionAt(21))
        assertNull(TokenizeResult.empty(0).embeddedRegionAt(0))
        assertFailsWith<IllegalArgumentException> {
            TokenizeResult(0, emptyList(), emptyList(), embeddedRegions = listOf(TextRange(10, 20), TextRange(15, 25)))
        }
    }

    @Test
    fun `one merge rule drops the parser's lexer copies, deduplicates and sorts`() {
        fun diagnostic(start: Int, code: String, source: DiagnosticSource) =
            Diagnostic(TextRange(start, start + 1), Severity.ERROR, code, code, source)
        val lexer = diagnostic(3, DiagnosticCodes.LEXER_ERROR, DiagnosticSource.LEXER)
        val parser = diagnostic(1, DiagnosticCodes.SYNTAX_ERROR, DiagnosticSource.PARSER)
        val standalone = diagnostic(0, "kstates.initial-state-count", DiagnosticSource.DSL_TOOLS)
        val project = diagnostic(5, "kmodel.unknown-property", DiagnosticSource.DSL_TOOLS)
        val parse = ParseOutcome(
            KarpfenLanguage.KMETA, 0, listOf(lexer, parser), emptyList(), SyntaxIndex.EMPTY, emptyList(), emptyList(),
            standaloneModel = null, standaloneDiagnostics = listOf(standalone),
        )

        assertEquals(listOf(standalone, parser, lexer, project), mergeDiagnostics(listOf(lexer), parse, listOf(listOf(project, standalone))))
        assertEquals(listOf(lexer), mergeDiagnostics(listOf(lexer), parse = null))
        val snapshot = TextSnapshot("abc", 4)
        assertEquals(4, VersionedDiagnostics(snapshot, listOf(lexer)).version)
    }

    @Test
    fun `the syntax index finds symbols by offset, name and kind`() {
        val robot = SymbolDeclaration(SymbolKind.TYPE, "Robot", TextRange(6, 11), TextRange(0, 40), container = null)
        val log = SymbolDeclaration(SymbolKind.SIMPLE_PROPERTY, "log", TextRange(25, 28), TextRange(19, 38), container = "Robot")
        val vector = SymbolReference(ReferenceKind.TYPE, "Vector", TextRange(52, 58), qualifier = null)
        val index = SyntaxIndex(listOf(log, robot), listOf(vector))

        assertEquals(listOf(robot, log), index.declarations)
        assertSame(robot, index.declarationAt(11))
        assertSame(vector, index.referenceAt(52))
        assertEquals(listOf(log), index.declarations(SymbolKind.SIMPLE_PROPERTY, "log"))
        assertEquals(listOf(robot, log), index.enclosingDeclarations(26))
        assertEquals(SyntaxIndex(listOf(robot, log), listOf(vector)), index)
        assertFailsWith<IllegalArgumentException> {
            SymbolDeclaration(SymbolKind.TYPE, "X", TextRange(50, 51), TextRange(0, 40), container = null)
        }
    }

    @Test
    fun `outline paths lead to the innermost node at an offset`() {
        val inner = OutlineNode(SymbolKind.STATE, "b", null, TextRange(10, 20), TextRange(17, 18), emptyList())
        val outer = OutlineNode(SymbolKind.STATE, "a", null, TextRange(0, 30), TextRange(7, 8), listOf(inner))

        assertEquals(listOf(outer, inner), listOf(outer).pathAt(15))
        assertEquals(listOf(outer), listOf(outer).pathAt(25))
        assertEquals(emptyList(), listOf(outer).pathAt(31))
    }

    @Test
    fun `registries resolve supports by language and file name`() {
        val tokenizer = Tokenizer { snapshot -> TokenizeResult.empty(snapshot.version) }
        val parser = object : DocumentParser {
            override fun parse(snapshot: TextSnapshot, mode: SemanticMode): ParseOutcome = error("not used")
        }
        val kmeta = LanguageSupport(KarpfenLanguage.KMETA, tokenizer, parser, lineComment = "//")
        val registry = LanguageRegistry(listOf(kmeta))

        assertSame(kmeta, registry.supportFor("a.kmeta"))
        assertNull(registry.languageOf("a.kstates"))
        assertFailsWith<IllegalArgumentException> { registry.support(KarpfenLanguage.KSTATES) }
        assertFailsWith<IllegalArgumentException> { LanguageRegistry(listOf(kmeta, kmeta)) }
    }

    @Test
    fun `the default registry wires every built-in language without parsing`() {
        val registry = defaultLanguageRegistry()

        assertEquals(KarpfenLanguage.entries.toSet(), registry.languages)
        KarpfenLanguage.entries.forEach { assertEquals(it, registry.support(it).language) }
        assertEquals(KarpfenLanguage.KMODEL, registry.languageOf("model.kmodel"))
        assertEquals(KarpfenLanguage.ENVIRONMENT, registry.languageOf("robot.kenv.json"))
        assertEquals(KarpfenLanguage.VIS, registry.languageOf("lamp.kstates.vis"))
        assertEquals(
            listOf(KarpfenLanguage.ENVIRONMENT, KarpfenLanguage.VIS),
            KarpfenLanguage.entries.filterNot { it.isModelingLanguage },
        )
        assertEquals(
            mapOf(
                KarpfenLanguage.KMETA to "//", KarpfenLanguage.KMODEL to "//", KarpfenLanguage.KSTATES to "//",
                KarpfenLanguage.ENVIRONMENT to null, KarpfenLanguage.VIS to null,
            ),
            KarpfenLanguage.entries.associateWith { registry.support(it).lineComment },
            "JSON has no comments",
        )
    }

    @Test
    fun `document ids are absolute and normalized`() {
        val id = DocumentId.of(Path.of("examples", "..", "examples", "robot.kstates"))

        assertEquals("robot.kstates", id.fileName)
        assertEquals(id.path.normalize(), id.path)
        assertFailsWith<IllegalArgumentException> { DocumentId(Path.of("relative.kmeta")) }
    }
}
