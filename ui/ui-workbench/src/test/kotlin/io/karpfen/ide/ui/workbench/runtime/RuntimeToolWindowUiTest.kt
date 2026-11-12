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
package io.karpfen.ide.ui.workbench.runtime

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.karpfen.ide.runtime.host.HostStatus
import io.karpfen.ide.runtime.host.RuntimeHostKind
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.runtime.session.events.InjectionAvailability
import io.karpfen.ide.runtime.session.fixtures.FakeRuntimeSession
import io.karpfen.ide.runtime.session.fixtures.FakeSessionCommand
import io.karpfen.ide.ui.foundation.widgets.StatusBarTestTags
import io.karpfen.ide.ui.workbench.WorkbenchUi
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.runtime.view.RuntimeViewTestTags
import io.karpfen.ide.ui.workbench.testing.FakeRuntimeHost
import io.karpfen.ide.ui.workbench.workbenchUiTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the composed Runtime tool window over a [FakeRuntimeHost] and runs whose sessions are [FakeRuntimeSession]s
 * that the test drives. The tests cover the explanation in the empty window, the runs list with the selected run, and
 * the Send button of the event injector. They also cover the header with the host's status, its buttons and the host
 * selector, and the runtime segment of the status bar.
 */
@OptIn(ExperimentalTestApi::class)
class RuntimeToolWindowUiTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()

    @Test
    fun `without runs the tool window tells how to run and what Run would run`() = workbenchUiTest(dir) {
        openLamp()
        workbench.state.layout.showToolWindow(ToolWindowId.RUNTIME)
        settle()

        tagged(RuntimeViewTestTags.EMPTY).assertTextContains("F10", substring = true)
        tagged(RuntimeViewTestTags.EMPTY).assertTextContains("It runs ${root.relativize(lamp)}.", substring = true)
    }

    @Test
    fun `the runs line shows the selected run with its phase, and Stop stops it`() = workbenchUiTest(dir, scriptSessions = true) {
        openLamp()
        val session = launched()

        session.stateFlow.value = SessionState.Running(EnvironmentKey(ENV_1))
        settle()

        phase(1).assertTextEquals("running")
        tagged(RuntimeViewTestTags.run(1)).assertTextContains(ENV_1, substring = true)
        tagged(RuntimeViewTestTags.STOP).assertIsEnabled()

        tagged(RuntimeViewTestTags.STOP).performClick()
        settle()

        assertEquals(listOf<FakeSessionCommand>(FakeSessionCommand.Stop), session.commands)
        phase(1).assertTextEquals("stopped")
        tagged(RuntimeViewTestTags.STOP).assertIsNotEnabled()
    }

    @Test
    fun `Send is enabled only while events can be injected and the payload has no error`() = workbenchUiTest(dir, scriptSessions = true) {
        openLamp()
        val session = launched()
        workbench.state.layout.resizeToolWindow(ToolWindowId.RUNTIME, TALL)
        settle()

        tagged(RuntimeViewTestTags.domain("switch")).performClick()
        settle()
        tagged(RuntimeViewTestTags.injectable("dim")).performClick()
        settle()

        tagged(RuntimeViewTestTags.SEND).assertIsNotEnabled()

        session.availabilityFlow.value = InjectionAvailability.Live
        settle()

        tagged(RuntimeViewTestTags.SEND).assertIsEnabled()

        tagged(RuntimeViewTestTags.PAYLOAD).performTextReplacement("{\"bright\": 3}")
        settle()

        tagged(RuntimeViewTestTags.SEND).assertIsNotEnabled()
        tagged(RuntimeViewTestTags.diagnostic(0)).assertExists()

        tagged(RuntimeViewTestTags.PAYLOAD).performTextReplacement("{\"bright\": true}")
        settle()
        tagged(RuntimeViewTestTags.SEND).performScrollTo()
        tagged(RuntimeViewTestTags.SEND).performClick()
        settle()

        assertEquals(1, session.frames.size)
        tagged(RuntimeViewTestTags.OUTCOME).assertExists()
    }

    @Test
    fun `the header shows the host's status, starts and stops the runtime and switches the host`() = workbenchUiTest(dir) {
        openLamp()
        val runtime = assertNotNull(workbench.state.runtime.value)
        assertFalse(workbench.state.layout.state.value.toolWindow(ToolWindowId.RUNTIME).visible)

        tagged(StatusBarTestTags.item(RUNTIME_SEGMENT)).assertTextContains("Runtime: stopped", substring = true)
        tagged(StatusBarTestTags.item(RUNTIME_SEGMENT)).performClick()
        settle()

        assertTrue(workbench.state.layout.state.value.toolWindow(ToolWindowId.RUNTIME).visible, "the segment shows the tool window")
        tagged(RuntimeViewTestTags.HOST_STATUS).assertTextEquals("Docker: not started")

        tagged(RuntimeViewTestTags.START_RUNTIME).performClick()
        settle()

        val docker = workbench.runtimeHosts.current
        assertEquals(1, docker.starts)

        docker.statusFlow.value = HostStatus.Running(FakeRuntimeHost.ENDPOINT, "karpfen runtime")
        settle()

        tagged(RuntimeViewTestTags.HOST_STATUS).assertTextEquals("Docker: running at 127.0.0.1:8080")
        tagged(StatusBarTestTags.item(RUNTIME_SEGMENT)).assertTextContains("Runtime: running", substring = true)

        tagged(RuntimeViewTestTags.STOP_RUNTIME).performClick()
        settle()

        assertEquals(1, docker.stops)

        tagged(RuntimeViewTestTags.host(RuntimeHostKind.URL)).performClick()
        settle()

        assertEquals(1, docker.closes)
        assertEquals(RuntimeHostKind.URL, workbench.runtimeHosts.current.settings.kind)
        tagged(RuntimeViewTestTags.HOST_STATUS).assertTextEquals(RuntimeTexts.hostStatus(runtime.hostSettings.value, HostStatus.Stopped))
        tagged(RuntimeViewTestTags.URL_FIELD).assertExists()
    }

    private val lamp: Path get() = root.resolve(SynthesizedModel.LAMP.folder).resolve(SynthesizedModel.LAMP.descriptorName)

    /** The phase label of the run [id], which its clickable row merges. */
    private fun WorkbenchUi.phase(id: Long): SemanticsNodeInteraction =
        test.onNodeWithTag(RuntimeViewTestTags.phase(id), useUnmergedTree = true)

    /** Copies the lamp into the workspace and opens it. */
    private suspend fun WorkbenchUi.openLamp() {
        SynthesizedModels.copyTo(root.resolve(SynthesizedModel.LAMP.folder), SynthesizedModel.LAMP, DescriptorTiming.EVENT_SCENARIOS)
        workbench.open(root)
        settle()
    }

    /** Runs the lamp and returns the session that the run launched. */
    private fun WorkbenchUi.launched(): FakeRuntimeSession {
        assertNotNull(workbench.state.runtime.value).run(lamp)
        settle()
        return workbench.scriptedSessions.launched.single()
    }

    private companion object {
        /** The environment the runs line shows. */
        const val ENV_1 = "env-1"

        /** The status bar segment of the runtime. */
        const val RUNTIME_SEGMENT = "runtime"

        /** A Runtime tool window tall enough for the event log and the injector. */
        const val TALL = 600f
    }
}
