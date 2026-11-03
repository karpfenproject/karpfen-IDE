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
package io.karpfen.ide.runtime.protocol.conformance

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [SynthesizedModels]: the models stay Python-free except the depot, which evaluates Python only in its two
 * creating actions; every descriptor names files that exist and names no timing; the attachments and domains are the
 * ones the scenarios rely on; and a copy writes exactly the named files, with the timing a scenario asks for.
 */
class SynthesizedModelsTest {

    @TempDir
    lateinit var directory: Path

    /** Every file of the folder of [model]: the descriptor, the files it names and the failure inputs. */
    private fun allTexts(model: SynthesizedModel): Map<String, String> =
        (listOf(model.descriptorName) + SynthesizedModels.files(model).all + SynthesizedModels.failureInputs(model))
            .associateWith { SynthesizedModels.text(model, it) }

    @Test
    fun `no model but the depot mentions EVAL or MACRO`() {
        val python = Regex("""\b(EVAL|MACRO)\b""")
        SynthesizedModel.entries.filter { it != SynthesizedModel.DEPOT }.forEach { model ->
            allTexts(model).forEach { (name, text) -> assertFalse(python.containsMatchIn(text), "${model.folder}/$name mentions Python") }
        }
    }

    @Test
    fun `the depot evaluates Python only in its two creating actions`() {
        val evaluating = allTexts(SynthesizedModel.DEPOT).flatMap { (name, text) ->
            text.lines().filter { Regex("""\b(EVAL|MACRO)\b""").containsMatchIn(it) }.map { name to it.trim() }
        }

        assertEquals(
            listOf(
                "depot.kstates" to """SETOBJ("self", "slot", EVAL { return {"label": "received"} })""",
                "depot.kstates" to """APPENDOBJ("self", "stack", EVAL { return {"label": "stacked"} })""",
            ),
            evaluating,
        )
    }

    @Test
    fun `every descriptor names files that exist and no timing`() {
        SynthesizedModel.entries.forEach { model ->
            val descriptor = SynthesizedModels.descriptorText(model)
            SynthesizedModels.files(model).all.forEach { name ->
                assertTrue(SynthesizedModels.text(model, name).isNotBlank(), "${model.folder}/$name")
            }
            assertFalse("tickDelayMs" in descriptor || "eventTtlMs" in descriptor || "observe" in descriptor, descriptor)
        }
    }

    @Test
    fun `the descriptors attach the machines the scenarios rely on`() {
        assertEquals(
            mapOf(
                SynthesizedModel.LAMP to listOf("lamp.kstates" to "lamp", "switch.kstates" to "wallSwitch"),
                SynthesizedModel.METRONOME to listOf("metronome.kstates" to "metronome"),
                SynthesizedModel.DEPOT to listOf("depot.kstates" to "depot"),
                SynthesizedModel.RING to listOf("ring.kstates" to "a"),
            ),
            SynthesizedModel.entries.associateWith { model -> SynthesizedModels.files(model).machines.map { it.file to it.attachedTo } },
        )
        assertEquals(null, SynthesizedModels.files(SynthesizedModel.RING).events)
        assertEquals("lamp-events.kmeta", SynthesizedModels.files(SynthesizedModel.LAMP).events)
    }

    @Test
    fun `no event domain is named like an attached object key`() {
        val domain = Regex("""EVENT\("([^"]*)"""")
        SynthesizedModel.entries.forEach { model ->
            val files = SynthesizedModels.files(model)
            val domains = files.machines.flatMap { machine -> domain.findAll(SynthesizedModels.text(model, machine.file)).map { it.groupValues[1] } }
            assertEquals(emptySet(), domains.toSet() intersect files.machines.map { it.attachedTo }.toSet(), model.folder)
        }
    }

    @Test
    fun `the failure input differs from the lamp's model only in the type of the spare bulb`() {
        val model = SynthesizedModels.text(SynthesizedModel.LAMP, "lamp.kmodel")
        val unknownType = SynthesizedModels.text(SynthesizedModel.LAMP, SynthesizedModels.LAMP_UNKNOWN_TYPE)

        assertEquals(model.replace("\"spare\":\"Bulb\"", "\"spare\":\"Bulbb\""), unknownType)
        assertTrue(model != unknownType)
    }

    @Test
    fun `a copy writes the descriptor and exactly the files it names`() {
        val descriptor = SynthesizedModels.copyTo(directory, SynthesizedModel.LAMP)

        assertEquals(directory.resolve("lamp.kenv.json"), descriptor)
        assertEquals(SynthesizedModels.descriptorText(SynthesizedModel.LAMP), Files.readString(descriptor))
        val written = Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() }.sorted()
        assertEquals((SynthesizedModels.files(SynthesizedModel.LAMP).all + "lamp.kenv.json").sorted(), written)
        SynthesizedModels.files(SynthesizedModel.LAMP).all.forEach { name ->
            assertEquals(SynthesizedModels.text(SynthesizedModel.LAMP, name), Files.readString(directory.resolve(name)))
        }
    }

    @Test
    fun `a copy adds the timing a scenario asks for as the last members of the descriptor`() {
        val both = SynthesizedModels.copyTo(directory.resolve("both"), SynthesizedModel.METRONOME, DescriptorTiming.EVENT_SCENARIOS)
        val tick = SynthesizedModels.copyTo(directory.resolve("tick"), SynthesizedModel.RING, DescriptorTiming(tickDelayMs = 0))

        assertEquals(
            """
            {
              "metamodel": "metronome.kmeta",
              "events": "metronome-events.kmeta",
              "model": "metronome.kmodel",
              "stateMachines": [
                { "file": "metronome.kstates", "attachedTo": "metronome" }
              ],
              "tickDelayMs": 50,
              "eventTtlMs": 30000
            }

            """.trimIndent(),
            Files.readString(both),
        )
        assertTrue(Files.readString(tick).endsWith("  ],\n  \"tickDelayMs\": 0\n}\n"), Files.readString(tick))
    }
}
