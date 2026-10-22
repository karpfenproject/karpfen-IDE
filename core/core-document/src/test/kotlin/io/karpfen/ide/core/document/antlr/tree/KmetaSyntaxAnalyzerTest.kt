package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.DiagnosticSource
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.FoldingRange
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [KmetaSyntaxAnalyzer]: declarations, type references, outline, semantic spans and folding of KMeta, the
 * structure kept on broken text, and the declarations of the examples.
 */
class KmetaSyntaxAnalyzerTest {

    private val text = """
        // Geometry.
        // Shared by every type.
        type "Vector" "A vector" {
            prop("x", "number")
            prop("tags", list("string"))
        }

        type "Robot" "A robot" {
            has("position", "Vector")
            knows("obstacles", list("Obstacle"))
        }
    """.trimIndent()

    private val analysis = analyze(KarpfenLanguage.KMETA, text)

    @Test
    fun `types and rules become declarations with containers and type names`() {
        assertEquals(
            listOf(
                Triple(SymbolKind.TYPE, "Vector", null),
                Triple(SymbolKind.SIMPLE_PROPERTY, "x", "Vector"),
                Triple(SymbolKind.SIMPLE_PROPERTY, "tags", "Vector"),
                Triple(SymbolKind.TYPE, "Robot", null),
                Triple(SymbolKind.HAS_RELATION, "position", "Robot"),
                Triple(SymbolKind.KNOWS_RELATION, "obstacles", "Robot"),
            ),
            analysis.index.declarations.map { Triple(it.kind, it.name, it.container) },
        )
        assertEquals(listOf(null, "number", "list(string)", null, "Vector", "list(Obstacle)"), analysis.index.declarations.map { it.typeName })
        val vector = analysis.index.declarations(SymbolKind.TYPE, "Vector").single()
        assertEquals("Vector", text.at(vector.nameRange), "the name range lies inside the quotes")
        assertTrue(text.at(vector.range).startsWith("type \"Vector\"") && text.at(vector.range).endsWith("}"))
    }

    @Test
    fun `type strings are primitive or type references`() {
        assertEquals(
            listOf(
                ReferenceKind.PRIMITIVE_TYPE to "number",
                ReferenceKind.PRIMITIVE_TYPE to "string",
                ReferenceKind.TYPE to "Vector",
                ReferenceKind.TYPE to "Obstacle",
            ),
            analysis.index.references.map { it.kind to it.name },
        )
        assertTrue(analysis.index.references.all { it.qualifier == null && text.at(it.range) == it.name })
    }

    @Test
    fun `the outline nests the rules under their type with the documented details`() {
        val outline = analysis.outline
        assertEquals(listOf("Vector" to "A vector", "Robot" to "A robot"), outline.map { it.name to it.detail })
        assertEquals(
            listOf("x" to "number", "tags" to "list(string)"),
            outline[0].children.map { it.name to it.detail },
        )
        assertEquals(listOf(SymbolKind.HAS_RELATION, SymbolKind.KNOWS_RELATION), outline[1].children.map { it.kind })
        assertEquals("position", text.at(outline[1].children[0].selectionRange))
    }

    @Test
    fun `semantic spans classify names, descriptions and types`() {
        val spans = analysis.semanticSpans.map { it.kind to text.at(it.range) }
        assertEquals(
            listOf(
                SemanticKind.TYPE_NAME to "Vector", SemanticKind.DESCRIPTION to "A vector",
                SemanticKind.PROPERTY_NAME to "x", SemanticKind.PRIMITIVE_TYPE to "number",
                SemanticKind.PROPERTY_NAME to "tags", SemanticKind.PRIMITIVE_TYPE to "string",
                SemanticKind.TYPE_NAME to "Robot", SemanticKind.DESCRIPTION to "A robot",
                SemanticKind.PROPERTY_NAME to "position", SemanticKind.TYPE_NAME to "Vector",
                SemanticKind.PROPERTY_NAME to "obstacles", SemanticKind.TYPE_NAME to "Obstacle",
            ),
            spans,
        )
    }

    @Test
    fun `multi-line blocks and comment runs fold`() {
        val firstType = text.indexOf('{')
        val secondType = text.lastIndexOf("type")
        assertEquals(
            listOf(
                FoldingRange(TextRange(0, text.indexOf('\n', text.indexOf('\n') + 1)), FoldingKind.COMMENT),
                FoldingRange(TextRange(firstType, text.indexOf('}') + 1), FoldingKind.BLOCK),
                FoldingRange(TextRange(text.indexOf('{', secondType), text.length), FoldingKind.BLOCK),
            ),
            analysis.folding,
        )
        assertEquals(emptyList(), analyze(KarpfenLanguage.KMETA, "type \"A\" \"d\" { }\n// one comment\n\n// another").folding)
    }

    @Test
    fun `conjured tokens and recovery keep the structure`() {
        val broken = "type \"T\" {\n    prop(\"x\" \"number\")\n}\ntype \"U\" \"e\" { has(\"k\", \"T\") }"

        val result = analyze(KarpfenLanguage.KMETA, broken)

        assertTrue(result.syntaxDiagnostics.isNotEmpty() && result.syntaxDiagnostics.all { it.source == DiagnosticSource.PARSER })
        assertEquals(listOf("T" to null, "U" to "e"), result.outline.map { it.name to it.detail }, "a conjured description is no detail")
        assertEquals(
            listOf(Triple("T", null, null), Triple("x", "T", "number"), Triple("U", null, null), Triple("k", "U", "T")),
            result.index.declarations.map { Triple(it.name, it.container, it.typeName) },
        )
        checkInvariants(broken, result, "broken KMeta")
    }

    @Test
    fun `the examples yield the expected declarations`() {
        val file = Examples.file("karpfen-runtime/single_robot_example/cleaning_robot.kmeta")
        val result = analyze(KarpfenLanguage.KMETA, file.text)

        assertEquals(
            listOf("Vector", "TwoDObject", "Obstacle", "Wall", "Robot", "Room"),
            result.index.declarationsOf(SymbolKind.TYPE).map { it.name },
        )
        val robot = result.index.declarations.filter { it.container == "Robot" }
        assertEquals(9, robot.size)
        assertEquals("list(Obstacle)", robot.single { it.name == "obstacles" }.typeName)
        assertEquals(SymbolKind.KNOWS_RELATION, robot.single { it.name == "closest_wall" }.kind)
        assertEquals(6, result.folding.count { it.kind == FoldingKind.BLOCK })

        val events = analyze(KarpfenLanguage.KMETA, Examples.file("karpfen-runtime/single_robot_example/EVENTS.kmeta").text)
        assertEquals(listOf("start", "setSpeed", "teleport"), events.outline.map { it.name })
        assertEquals(listOf(FoldingKind.COMMENT), events.folding.filter { it.kind == FoldingKind.COMMENT }.map { it.kind })
    }
}
