package io.karpfen.ide.core.workspace.analysis

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [ContextSymbols] over loose files parsed syntax-only, as the analyzer leaves them, and the ranking rule of
 * [AttachmentCandidate.rank] on its own.
 */
class ContextSymbolsTest {

    private val directory: Path = Path.of("/workspace/lab").toAbsolutePath().normalize()
    private val metamodel = directory.resolve("lab.kmeta")
    private val model = directory.resolve("lab.kmodel")
    private val robotMachine = directory.resolve("robot.kstates")
    private val roomMachine = directory.resolve("room.kstates")

    private val symbols = ContextSymbols(
        analysisOf(
            metamodel to "type \"Room\" \"a room\" { }\ntype \"Robot\" \"a robot\" { }\n",
            model to """
                make object "lab":"Room" {
                    has("first") -> make object "r1":"Robot" { }
                    has("second") -> make object "r2":"Robot" { }
                    has("again") -> make object "lab":"Robot" { }
                }
            """.trimIndent(),
            robotMachine to machine("Robot", "EVENT(\"public\", \"go\")\nEVENT(\"internal\", \"x\")"),
            roomMachine to machine("Room", "EVENT(\"public\", \"stop\")"),
        ),
    )

    @Test
    fun `types, objects and attached types come from the syntax indexes`() {
        assertEquals(listOf("Room", "Robot"), symbols.typeNamesOf(metamodel))
        assertEquals(
            listOf(ModelObject("lab", "Room"), ModelObject("r1", "Robot"), ModelObject("r2", "Robot"), ModelObject("lab", "Robot")),
            symbols.objectsOf(model),
        )
        assertEquals("Robot", symbols.attachedTypeOf(robotMachine))
        assertNull(symbols.attachedTypeOf(directory.resolve("unknown.kstates")))
        assertEquals(emptyList(), symbols.objectsOf(directory.resolve("unknown.kmodel")))
    }

    @Test
    fun `event domains of several state machines are sorted and each named once`() {
        assertEquals(listOf("internal", "public"), symbols.eventDomainsOf(listOf(robotMachine, roomMachine)))
        assertEquals(listOf("public"), symbols.eventDomainsOf(listOf(roomMachine, directory.resolve("unknown.kstates"))))
    }

    @Test
    fun `objects of the attached type rank first, and a repeated key is proposed once at its best place`() {
        val forRobot = symbols.attachmentCandidates(model, robotMachine)
        assertEquals(listOf("r1", "r2", "lab"), forRobot.map { it.modelObject.key })
        assertEquals(listOf(true, true, true), forRobot.map { it.suitsMachine }, "the Robot named lab wins over the Room")

        val forRoom = symbols.attachmentCandidates(model, roomMachine)
        assertEquals(listOf("lab" to true, "r1" to false, "r2" to false), forRoom.map { it.modelObject.key to it.suitsMachine })

        assertEquals(listOf("lab", "r1", "r2"), symbols.attachmentCandidates(model, machine = null).map { it.modelObject.key })
    }

    @Test
    fun `the ranking keeps the given order within each group and leaves out objects without a key`() {
        val objects = listOf(
            ModelObject("a", "T"), ModelObject("", "R"), ModelObject("b", "R"), ModelObject("c", null), ModelObject("d", "R"),
        )

        assertEquals(listOf("b", "d", "a", "c"), AttachmentCandidate.rank(objects, "R").map { it.modelObject.key })
        assertEquals(listOf("a", "b", "c", "d"), AttachmentCandidate.rank(objects, null).map { it.modelObject.key })
        assertEquals(emptyList(), AttachmentCandidate.rank(listOf(ModelObject(" ", "R")), "R"))
    }

    /** A state machine attached to [type] whose initial state's entry runs [statements]. */
    private fun machine(type: String, statements: String): String =
        "STATEMACHINE ATTACHED TO \"$type\" {\n    STATES {\n        INITIAL STATE \"s\" {\n            ENTRY {\n" +
            statements.lines().joinToString("") { "                $it\n" } +
            "            }\n        }\n    }\n}\n"

    /** An analysis that knows [files] (path and text) as loose files, parsed syntax-only. */
    private fun analysisOf(vararg files: Pair<Path, String>): ProjectAnalysis {
        val registry = defaultLanguageRegistry()
        val loose = files.associate { (path, text) ->
            val name = path.fileName.toString()
            val support = registry.support(requireNotNull(KarpfenLanguage.fromFileName(name)))
            val snapshot = TextSnapshot(text, 0)
            path to AnalyzedFile(path, FileKind.of(name), snapshot, support.parser.parse(snapshot, SemanticMode.SYNTAX_ONLY))
        }
        return ProjectAnalysis(generation = 1, contexts = emptyList(), diagnostics = emptyMap(), looseFiles = loose)
    }
}
