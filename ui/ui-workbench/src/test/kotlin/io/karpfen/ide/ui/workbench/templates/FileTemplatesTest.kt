package io.karpfen.ide.ui.workbench.templates

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptor
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FileTemplates]: file names, parse-clean sources for any name, and the environment template's attach rule. */
class FileTemplatesTest {

    private val templates = FileTemplates()
    private val languages = defaultLanguageRegistry()
    private val directory: Path = Path.of("/ws/robot").toAbsolutePath()

    private fun assertClean(kind: FileTemplateKind, text: String, label: String) {
        val language = when (kind) {
            FileTemplateKind.KMETA -> KarpfenLanguage.KMETA
            FileTemplateKind.KMODEL -> KarpfenLanguage.KMODEL
            FileTemplateKind.KSTATES -> KarpfenLanguage.KSTATES
            FileTemplateKind.ENVIRONMENT -> null
        }
        if (language == null) {
            val read = EnvironmentDescriptorReader().read(directory.resolve("x.kenv.json"), text)
            assertEquals(emptyList(), read.diagnostics, "$label: $text")
            return
        }
        val support = languages.support(language)
        val snapshot = TextSnapshot(text, 0)
        assertEquals(emptyList(), support.tokenizer.tokenize(snapshot).diagnostics, "$label tokens: $text")
        assertEquals(emptyList(), support.parser.parse(snapshot).syntaxDiagnostics, "$label parse: $text")
    }

    @Test
    fun `file names get the kind's suffix once`() {
        assertEquals("robot.kmeta", templates.fileName(FileTemplateKind.KMETA, "robot"))
        assertEquals("robot.kmeta", templates.fileName(FileTemplateKind.KMETA, " robot.KMETA ".trim().lowercase()))
        assertEquals("Robot.KMETA", templates.fileName(FileTemplateKind.KMETA, "Robot.KMETA"))
        assertEquals("env.kenv.json", templates.fileName(FileTemplateKind.ENVIRONMENT, "env"))
        assertEquals("env.kenv.json", templates.fileName(FileTemplateKind.ENVIRONMENT, "env.kenv.json"))
    }

    @Test
    fun `sources name their type after the file or use the context's first type`() {
        assertTrue(templates.content(FileTemplateKind.KMETA, "cleaning_robot", TemplateContext(directory)).startsWith("type \"CleaningRobot\""))
        assertTrue(templates.content(FileTemplateKind.KMODEL, "room", TemplateContext(directory)).contains("make object \"room\":\"Room\""))
        val context = TemplateContext(directory, typeNames = listOf("Vector", "Robot"))
        assertTrue(templates.content(FileTemplateKind.KMODEL, "room", context).contains("\"room\":\"Vector\""))
        assertTrue(templates.content(FileTemplateKind.KSTATES, "m", context).contains("ATTACHED TO \"Vector\""))
        assertTrue(templates.content(FileTemplateKind.KSTATES, "m.kstates", TemplateContext(directory)).contains("ATTACHED TO \"M\""))
        assertEquals("Example", KarpfenSourceTemplates.typeName("__"))
        assertEquals("T2d", KarpfenSourceTemplates.typeName("2d"))
    }

    @Test
    fun `every template is clean for random names, fixed seed`() {
        val random = Random(20261021)
        val alphabet = "abcXYZ019_-. \"\\'äß{}()->:,"
        val names = listOf("robot", "a", "", "\"quoted\"", "back\\slash", "two words") + List(200) {
            String(CharArray(random.nextInt(1, 16)) { alphabet[random.nextInt(alphabet.length)] })
        }
        val contexts = listOf(
            TemplateContext(directory),
            TemplateContext(directory, typeNames = listOf("Vec\"tor"), objects = listOf(TemplateObject("o\"1", "Vec\"tor"))),
        )
        names.forEach { name ->
            contexts.forEach { context ->
                FileTemplateKind.entries.forEach { kind -> assertClean(kind, templates.content(kind, name, context), "'$name' $kind") }
            }
        }
    }

    @Test
    fun `the environment template names the context's files relative to the directory`() {
        val context = ModelingContext(
            directory = directory,
            descriptor = null,
            metamodel = directory.resolve("cleaning_robot.kmeta"),
            events = directory.resolve("EVENTS.kmeta"),
            models = listOf(directory.resolve("cleaning_robot.kmodel"), directory.resolve("other.kmodel")),
            stateMachines = listOf(StateMachineBinding(directory.resolve("cleaning_robot.kstates"), null), StateMachineBinding(directory.resolve("sub/x.kstates"), null)),
        )
        val template = TemplateContext(
            directory, context,
            objects = listOf(TemplateObject("APB 2101", "Room"), TemplateObject("turtle", "Robot")),
            stateMachineTypes = mapOf(directory.resolve("cleaning_robot.kstates") to "Robot", directory.resolve("sub/x.kstates") to "Drone"),
        )
        val descriptor = Json.decodeFromString(EnvironmentDescriptor.serializer(), templates.content(FileTemplateKind.ENVIRONMENT, "env", template))
        assertEquals(
            EnvironmentDescriptor(
                metamodel = "cleaning_robot.kmeta",
                events = "EVENTS.kmeta",
                model = "cleaning_robot.kmodel",
                stateMachines = listOf(
                    EnvironmentDescriptor.StateMachineEntry("cleaning_robot.kstates", "turtle"),
                    EnvironmentDescriptor.StateMachineEntry("sub/x.kstates", "APB 2101"),
                ),
            ),
            descriptor,
            "attached by type, else to the first object",
        )
    }

    @Test
    fun `without a context the environment names files after itself and attaches to the placeholder`() {
        val descriptor = Json.decodeFromString(EnvironmentDescriptor.serializer(), templates.content(FileTemplateKind.ENVIRONMENT, "lab", TemplateContext(directory)))
        assertEquals(
            EnvironmentDescriptor("lab.kmeta", null, "lab.kmodel", listOf(EnvironmentDescriptor.StateMachineEntry("lab.kstates", EnvironmentTemplate.PLACEHOLDER_OBJECT))),
            descriptor,
        )
        assertEquals("object", EnvironmentTemplate.attachedTo(directory.resolve("x.kstates"), TemplateContext(directory, objects = listOf(TemplateObject("", "T")))))
    }
}
