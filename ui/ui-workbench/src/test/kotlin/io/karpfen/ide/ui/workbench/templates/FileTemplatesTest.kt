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
package io.karpfen.ide.ui.workbench.templates

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.EnvironmentDescriptor
import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.analysis.ModelObject
import io.karpfen.ide.core.workspace.context.ModelingContext
import io.karpfen.ide.core.workspace.context.StateMachineBinding
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileKind
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests [FileTemplates]. It covers the file names, sources that parse cleanly for any name, and the rule by which the
 * environment template attaches state machines.
 */
class FileTemplatesTest {

    private val templates = FileTemplates()
    private val languages = defaultLanguageRegistry()
    private val directory: Path = Path.of("/ws/robot").toAbsolutePath()

    private fun descriptorOf(text: String): EnvironmentDescriptor = Json.decodeFromString(EnvironmentDescriptor.serializer(), text)

    private fun assertClean(kind: FileTemplateKind, text: String, label: String) {
        val language = kind.language
        if (kind == FileTemplateKind.ENVIRONMENT) {
            val read = EnvironmentDescriptorReader().read(directory.resolve("x.kenv.json"), text)
            assertEquals(emptyList(), read.diagnostics, "$label: $text")
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
    fun `every kind creates files that the workspace recognizes in its language and names it after the language`() {
        FileTemplateKind.entries.forEach { kind ->
            val name = templates.fileName(kind, "robot")
            assertEquals(kind.language, KarpfenLanguage.fromFileName(name), name)
            assertEquals(kind.language, FileKind.of(name).language, name)
            assertEquals("${kind.language.displayName} File", kind.displayName)
        }
        assertEquals(".kenv.json", FileTemplateKind.ENVIRONMENT.suffix)
        assertEquals("Environment File", FileTemplateKind.ENVIRONMENT.displayName)
    }

    @Test
    fun `sources name their type after the file or use the context's first type`() {
        val metamodel = templates.content(FileTemplateKind.KMETA, "cleaning_robot", TemplateContext(directory))
        assertTrue(metamodel.startsWith("type \"CleaningRobot\""))
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
            TemplateContext(directory, typeNames = listOf("Vec\"tor"), objects = listOf(ModelObject("o\"1", "Vec\"tor"))),
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
            stateMachines = listOf("cleaning_robot.kstates", "sub/x.kstates").map { StateMachineBinding(directory.resolve(it), null) },
        )
        val template = TemplateContext(
            directory, context,
            objects = listOf(ModelObject("APB 2101", "Room"), ModelObject("turtle", "Robot")),
            stateMachineTypes = mapOf(
                directory.resolve("cleaning_robot.kstates") to "Robot",
                directory.resolve("sub/x.kstates") to "Drone",
            ),
        )
        val descriptor = descriptorOf(templates.content(FileTemplateKind.ENVIRONMENT, "env", template))
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
    fun `the environment template lists an attachable state machine as a definition without attachedTo`() {
        val machines = listOf("room.kstates", "glowing.kstates").map { directory.resolve(it) }
        val context = ModelingContext(directory, null, directory.resolve("spares.kmeta"), null, emptyList(), machines.map { StateMachineBinding(it, null) })
        val template = TemplateContext(
            directory, context,
            objects = listOf(ModelObject("room", "Room")),
            stateMachineTypes = mapOf(machines[0] to "Room", machines[1] to "Bulb"),
            definitions = setOf(machines[1]),
        )

        val descriptor = descriptorOf(templates.content(FileTemplateKind.ENVIRONMENT, "spares", template))

        assertEquals(
            listOf(EnvironmentDescriptor.StateMachineEntry("room.kstates", "room"), EnvironmentDescriptor.StateMachineEntry("glowing.kstates")),
            descriptor.stateMachines,
        )
    }

    @Test
    fun `the environment template observes every object and the event domains of the state machines`() {
        val descriptor = descriptorOf(
            templates.content(FileTemplateKind.ENVIRONMENT, "lab", TemplateContext(directory, eventDomains = listOf("internal", "public"))),
        )

        assertEquals(EnvironmentDescriptor.ObserveSpec(EnvironmentSchema.EVERY_OBJECT, listOf("internal", "public")), descriptor.observe)
        val text = templates.content(FileTemplateKind.ENVIRONMENT, "lab", TemplateContext(directory))
        assertTrue("observe" !in text && "events" !in text && "null" !in text, "keys left at their defaults are omitted: $text")
    }

    @Test
    fun `without a context the environment names files after itself and attaches to the placeholder`() {
        val descriptor = descriptorOf(templates.content(FileTemplateKind.ENVIRONMENT, "lab", TemplateContext(directory)))
        assertEquals(
            EnvironmentDescriptor(
                "lab.kmeta", null, "lab.kmodel",
                listOf(EnvironmentDescriptor.StateMachineEntry("lab.kstates", EnvironmentTemplate.PLACEHOLDER_OBJECT)),
            ),
            descriptor,
        )
        val anonymousOnly = TemplateContext(directory, objects = listOf(ModelObject("", "T")))
        assertEquals("object", EnvironmentTemplate.attachedTo(directory.resolve("x.kstates"), anonymousOnly))
    }
}
