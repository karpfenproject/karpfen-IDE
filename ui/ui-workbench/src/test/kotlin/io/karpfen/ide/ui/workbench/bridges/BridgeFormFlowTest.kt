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
package io.karpfen.ide.ui.workbench.bridges

import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the form that configures a bridge file, through the workbench. A bridge file opens in the form and never in a
 * tab. Configure Bridge picks a file or makes a new one. Saving writes the file, and it asks first when another tool
 * changed the file meanwhile.
 */
class BridgeFormFlowTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val settingsDirectory: Path get() = dir.resolve("settings")

    @Test
    fun `a bridge file opens in the form and not in a tab, and saving writes what the form holds`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)

            state.openFile(file).joinSettled()

            val form = assertNotNull(state.bridgeForm.value)
            assertEquals("door", form.name.value)
            assertEquals(file, form.file)
            assertFalse(form.isNew)
            assertEquals(emptyList(), form.fileProblems)
            assertEquals(listOf(InboundDraft("/press", "switch/press")), form.draft.value.inbound)
            assertTrue(state.tabs.tabs.value.isEmpty(), "a bridge file has no tab")

            form.edit { it.copy(settings = it.settings + ("port" to "9100"), outbound = listOf(OutboundDraft(Follows.STATE, "lamp", "http://localhost:9000/state"))) }
            state.saveBridgeForm().joinSettled()

            assertNull(state.bridgeForm.value, "saving closes the form")
            val saved = assertNotNull(BridgeConfigFile.read(Files.readString(file)).config)
            assertEquals(mapOf("port" to "9100"), saved.settings)
            assertEquals(1, saved.inbound.size)
            assertEquals("http://localhost:9000/state", saved.outbound.single().to)
        }
    }

    @Test
    fun `a form with a problem is not saved`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)
            state.openFile(file).joinSettled()
            val form = assertNotNull(state.bridgeForm.value)

            form.edit { it.copy(inbound = it.inbound + InboundDraft()) }
            state.saveBridgeForm().joinSettled()

            assertEquals(listOf("Inbound route 2 needs an address.", "Inbound route 2 needs an event as domain/name."), form.problems.value)
            assertEquals(form, state.bridgeForm.value, "the form stays open")
            assertEquals(HTTP_BRIDGE, Files.readString(file))

            state.closeBridgeForm()
            assertNull(state.bridgeForm.value)
            assertEquals(HTTP_BRIDGE, Files.readString(file), "Cancel writes nothing")
        }
    }

    @Test
    fun `Configure Bridge in a project without bridges opens a new form that needs a name and writes only on Save`() = runTest(timeout = TIMEOUT) {
        lamp()
        withWorkbench(settingsDirectory) {
            open(root)

            state.configureBridge().joinSettled()

            val file = root.resolve("door.kbridge.json")
            val form = assertNotNull(state.bridgeForm.value)
            assertTrue(form.isNew)
            assertEquals(BridgeDraft(BridgeKind.HTTP), form.draft.value)
            assertEquals(listOf("The bridge needs a name."), form.problems.value)
            state.saveBridgeForm().joinSettled()
            assertEquals(form, state.bridgeForm.value, "a bridge without a name is not saved")

            form.rename(" door ")
            assertEquals(file, form.file)
            assertEquals(emptyList(), form.problems.value)
            assertFalse(Files.exists(file), "nothing is written before Save")

            form.edit { BridgeDrafts.withKind(it, BridgeKind.MQTT).copy(inbound = listOf(InboundDraft("home/press", "switch/press"))) }
            state.saveBridgeForm().joinSettled()

            assertEquals(BridgeKind.MQTT, BridgeConfigFile.read(Files.readString(file)).config?.kind)
            assertEquals(listOf(file), workspace.files.value.files(FileKind.BRIDGE).map { it.path }, "the project tree knows the new file")
        }
    }

    @Test
    fun `Configure Bridge lists the bridges of the project and a new one`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)

            val first = state.configureBridge()
            val asked = answer(DialogRequest.ChooseBridgeFile::class, DialogResponse.Chosen(0))
            first.joinSettled()

            assertEquals(listOf(file), asked.bridges)
            assertEquals(file, state.bridgeForm.value?.file)
            state.closeBridgeForm()

            val second = state.configureBridge()
            answer(DialogRequest.ChooseBridgeFile::class, DialogResponse.Chosen(asked.newIndex))
            second.joinSettled()

            val created = assertNotNull(state.bridgeForm.value)
            assertTrue(created.isNew)
            created.rename("Door")
            assertEquals(listOf("Another bridge is named \"Door\" already."), created.problems.value)
            state.closeBridgeForm()

            val third = state.configureBridge()
            answer(DialogRequest.ChooseBridgeFile::class, DialogResponse.Cancel)
            third.joinSettled()

            assertNull(state.bridgeForm.value)
        }
    }

    @Test
    fun `a new name in the form renames the file on Save, and the remembered bridges follow`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)
            services.settings.update { it.copy(activeBridges = listOf("door.kbridge.json")) }
            state.openFile(file).joinSettled()
            val form = assertNotNull(state.bridgeForm.value)

            form.rename("a/b")
            assertEquals(listOf("The name of the bridge must not contain a slash."), form.problems.value)

            form.rename("gate")
            state.saveBridgeForm().joinSettled()

            val renamed = root.resolve("gate.kbridge.json")
            assertNull(state.bridgeForm.value)
            assertFalse(Files.exists(file), "the file of the old name is gone")
            assertEquals(BridgeConfigFile.read(HTTP_BRIDGE).config, BridgeConfigFile.read(Files.readString(renamed)).config)
            assertEquals(listOf(renamed), workspace.files.value.files(FileKind.BRIDGE).map { it.path })
            assertEquals(listOf("gate.kbridge.json"), state.settings.value.activeBridges)
        }
    }

    @Test
    fun `saving asks before it writes over a change another tool made meanwhile`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)
            state.openFile(file).joinSettled()
            val form = assertNotNull(state.bridgeForm.value)
            form.edit { it.copy(settings = mapOf("port" to "9100")) }
            val changed = HTTP_BRIDGE.replace("/press", "/push")
            Files.writeString(file, changed)

            val kept = state.saveBridgeForm()
            val asked = answer(DialogRequest.ConfirmOverwrite::class, DialogResponse.Cancel)
            kept.joinSettled()

            assertEquals(listOf(file), asked.paths)
            assertEquals(changed, Files.readString(file), "Cancel keeps the other tool's version")
            assertEquals(form, state.bridgeForm.value, "and keeps the form open")

            val written = state.saveBridgeForm()
            answer(DialogRequest.ConfirmOverwrite::class, DialogResponse.Confirm)
            written.joinSettled()

            assertNull(state.bridgeForm.value)
            assertEquals("/press", BridgeConfigFile.read(Files.readString(file)).config?.inbound?.single()?.from)
        }
    }

    @Test
    fun `a file with problems opens with what could be read, and saving writes it clean`() = runTest(timeout = TIMEOUT) {
        lamp()
        val broken = """{ "kind": "http", "inbound": [ { "from": "/press", "event": "switch/press" }, { "from": "/dim" } ] }"""
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), broken)
        val noBridge = TestWorkbench.write(root.resolve("other.kbridge.json"), "not json")
        withWorkbench(settingsDirectory) {
            open(root)

            state.openFile(file).joinSettled()

            val form = assertNotNull(state.bridgeForm.value)
            assertEquals(listOf("inbound[1] needs \"event\" as domain/name."), form.fileProblems)
            assertEquals(listOf(InboundDraft("/press", "switch/press")), form.draft.value.inbound)

            state.saveBridgeForm().joinSettled()

            assertEquals(emptyList(), BridgeConfigFile.read(Files.readString(file)).problems)

            state.openFile(noBridge).joinSettled()

            val empty = assertNotNull(state.bridgeForm.value)
            assertEquals(listOf("The file is not a JSON object."), empty.fileProblems)
            assertEquals(BridgeDraft(), empty.draft.value)
        }
    }

    @Test
    fun `the form offers the events, the properties and the state machines of the project to pick`() = runTest(timeout = TIMEOUT) {
        lamp()
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)

            state.openFile(file).joinSettled()

            val suggestions = assertNotNull(state.bridgeForm.value).suggestions
            assertEquals(listOf("service/smash", "switch/dim", "switch/press"), suggestions.events.sorted())
            assertEquals(suggestions.events.sorted(), suggestions.reportedEvents.sorted())
            assertEquals(listOf("lamp", "wallSwitch"), suggestions.machines)
            assertEquals(
                listOf("lamp/mode", "bulb/watts", "bulb/lit", "spare/watts", "spare/lit", "wallSwitch/position"),
                suggestions.properties.filterNot { it.startsWith("wallSwitch/presses") },
            )
            assertEquals(suggestions.properties, suggestions.followed(Follows.PROPERTY))
            assertEquals(suggestions.machines, suggestions.followed(Follows.STATE))
        }
    }

    @Test
    fun `a project that cannot be read offers what is left and still opens the form`() = runTest(timeout = TIMEOUT) {
        val descriptor = lamp()
        Files.writeString(descriptor.resolveSibling("lamp.kmeta"), "this is no metamodel")
        val file = TestWorkbench.write(root.resolve("door.kbridge.json"), HTTP_BRIDGE)
        withWorkbench(settingsDirectory) {
            open(root)

            state.openFile(file).joinSettled()

            val suggestions = assertNotNull(state.bridgeForm.value).suggestions
            assertEquals(emptyList(), suggestions.properties)
            assertEquals(listOf("lamp", "wallSwitch"), suggestions.machines)
            assertNoDialog()
        }
    }

    /** Copies the lamp model into the workspace root, so the workspace has a project to open. */
    private fun lamp(): Path = SynthesizedModels.copyTo(root, SynthesizedModel.LAMP, DescriptorTiming.EVENT_SCENARIOS)

    private companion object {
        val TIMEOUT = 60.seconds

        /** A bridge file with one inbound route. */
        val HTTP_BRIDGE: String = """
            {
              "kind": "http",
              "settings": {},
              "inbound": [
                { "from": "/press", "event": "switch/press" }
              ],
              "outbound": []
            }
        """.trimIndent() + "\n"
    }
}
