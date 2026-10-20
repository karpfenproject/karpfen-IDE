package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.parse.FoldingKind
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KmodelSyntaxAnalyzerTest {

    private val text = """
        make object "room":"Room" {
            prop("name") -> "Lab"
            has("robot") -> make object "turtle":"Robot" {
                has("position") -> make object "_":"Vector" {
                    prop("x") -> "1.0"
                }
                knows("target") -> 'table'
            }
            has("table") -> make object "":"Obstacle" { }
        }
    """.trimIndent()

    private val analysis = analyze(KarpfenLanguage.KMODEL, text)

    @Test
    fun `objects are declared with their container and type, anonymous ones without a name`() {
        assertEquals(
            listOf(
                Triple("room", null, "Room"),
                Triple("turtle", "room", "Robot"),
                Triple("", "turtle", "Vector"),
                Triple("", "room", "Obstacle"),
            ),
            analysis.index.declarations.map { Triple(it.name, it.container, it.typeName) },
        )
        assertTrue(analysis.index.declarations.all { it.kind == SymbolKind.OBJECT })
        val anonymous = analysis.index.declarations[2]
        assertEquals("_", text.at(anonymous.nameRange), "the name range is still the key")
        assertTrue(analysis.index.declarations[3].nameRange.isEmpty)
    }

    @Test
    fun `keys are qualified with the enclosing object's type`() {
        assertEquals(
            listOf(
                Triple(ReferenceKind.TYPE, "Room", null),
                Triple(ReferenceKind.PROPERTY_KEY, "name", "Room"),
                Triple(ReferenceKind.RELATION_KEY, "robot", "Room"),
                Triple(ReferenceKind.TYPE, "Robot", null),
                Triple(ReferenceKind.RELATION_KEY, "position", "Robot"),
                Triple(ReferenceKind.TYPE, "Vector", null),
                Triple(ReferenceKind.PROPERTY_KEY, "x", "Vector"),
                Triple(ReferenceKind.RELATION_KEY, "target", "Robot"),
                Triple(ReferenceKind.OBJECT, "table", null),
                Triple(ReferenceKind.RELATION_KEY, "table", "Room"),
                Triple(ReferenceKind.TYPE, "Obstacle", null),
            ),
            analysis.index.references.map { Triple(it.kind, it.name, it.qualifier) },
        )
        assertEquals("table", text.at(analysis.index.references(ReferenceKind.OBJECT, "table").single().range), "single quotes too")
    }

    @Test
    fun `the outline shows assignments and anonymous objects as colon type`() {
        val room = analysis.outline.single()
        assertEquals("room" to "Room", room.name to room.detail)
        assertEquals(
            listOf(
                SymbolKind.PROPERTY_ASSIGNMENT to "name",
                SymbolKind.HAS_ASSIGNMENT to "robot",
                SymbolKind.HAS_ASSIGNMENT to "table",
            ),
            room.children.map { it.kind to it.name },
        )
        assertEquals("Lab", room.children[0].detail)
        assertNull(room.children[1].detail)
        val turtle = room.children[1].children.single()
        assertEquals("turtle", turtle.name)
        assertEquals(listOf(":Vector"), turtle.children[0].children.map { it.name })
        assertEquals(SymbolKind.KNOWS_ASSIGNMENT to "table", turtle.children[1].kind to turtle.children[1].detail)
        assertEquals(":Obstacle", room.children[2].children.single().name)
    }

    @Test
    fun `semantic spans mark object names, types, keys and targets`() {
        assertEquals(
            listOf(
                SemanticKind.OBJECT_NAME to "room", SemanticKind.TYPE_NAME to "Room",
                SemanticKind.PROPERTY_NAME to "name",
                SemanticKind.PROPERTY_NAME to "robot", SemanticKind.OBJECT_NAME to "turtle", SemanticKind.TYPE_NAME to "Robot",
                SemanticKind.PROPERTY_NAME to "position", SemanticKind.TYPE_NAME to "Vector",
                SemanticKind.PROPERTY_NAME to "x",
                SemanticKind.PROPERTY_NAME to "target", SemanticKind.OBJECT_NAME to "table",
                SemanticKind.PROPERTY_NAME to "table", SemanticKind.TYPE_NAME to "Obstacle",
            ),
            analysis.semanticSpans.map { it.kind to text.at(it.range) },
        )
    }

    @Test
    fun `nested multi-line objects fold, single-line ones do not`() {
        assertEquals(listOf(0, 2, 3), analysis.folding.map { text.substring(0, it.range.start).count { c -> c == '\n' } })
        assertTrue(analysis.folding.all { it.kind == FoldingKind.BLOCK })
    }

    @Test
    fun `the examples yield the expected objects and references`() {
        val file = Examples.file("karpfen-dsl-tools/model_dsl_example.kmodel")
        val result = analyze(KarpfenLanguage.KMODEL, file.text)

        assertEquals(14, result.index.declarationsOf(SymbolKind.OBJECT).size)
        assertEquals(listOf("chair", "table"), result.index.referencesOf(ReferenceKind.OBJECT).map { it.name })
        assertEquals("turtleBoundingBox", result.index.declarations(SymbolKind.OBJECT, "turtlePosition").single().container)
        assertTrue(result.index.referencesOf(ReferenceKind.PROPERTY_KEY).filter { it.name == "diameter" }.all { it.qualifier == "TwoDObject" })

        val car = analyze(KarpfenLanguage.KMODEL, Examples.file("karpfen-runtime/autonomous_car_example/autonomous_car.kmodel").text)
        assertEquals(listOf("depot", "av-1", "carPosition", "carGoal"), car.index.declarations.map { it.name })
    }
}
