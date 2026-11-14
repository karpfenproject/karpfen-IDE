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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.conformance.DescriptorTiming
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModels
import io.karpfen.ide.runtime.session.SessionState
import io.karpfen.ide.ui.foundation.widgets.StatusBarTestTags
import io.karpfen.ide.ui.workbench.WorkbenchTestTags
import io.karpfen.ide.ui.workbench.WorkbenchUi
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.layout.ToolWindowId
import io.karpfen.ide.ui.workbench.layout.view.DetachedWindow
import io.karpfen.ide.ui.workbench.layout.view.DetachedWindowHost
import io.karpfen.ide.ui.workbench.layout.view.LayoutViewTestTags
import io.karpfen.ide.ui.workbench.runtime.view.RuntimeViewTestTags
import io.karpfen.ide.ui.workbench.settings.WindowBounds
import io.karpfen.ide.ui.workbench.workbenchUiTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests detaching the Runtime tool window in the composed workbench. The operating system window is replaced by
 * [InPlaceWindows], which draws the detached tool window inside the test's composition and records what the workbench
 * asked of the window. The tests cover the detach and dock buttons, closing the window, the run that carries over, and
 * the status bar while the tool window is detached.
 */
@OptIn(ExperimentalTestApi::class)
class RuntimeDetachUiTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val lamp: Path get() = root.resolve(SynthesizedModel.LAMP.folder).resolve(SynthesizedModel.LAMP.descriptorName)
    private val runtime = ToolWindowId.RUNTIME

    @Test
    fun `the detach button moves the tool window with its run into a window of its own, and the dock button brings it back`() {
        val windows = InPlaceWindows()
        workbenchUiTest(dir, scriptSessions = true, detachedWindows = windows) {
            openLamp()
            run()
            tagged(WorkbenchTestTags.toolWindow(runtime)).assertIsDisplayed()

            tagged(LayoutViewTestTags.placeButton(runtime)).performClick()
            settle()

            test.onNodeWithTag(WorkbenchTestTags.toolWindow(runtime)).assertDoesNotExist()
            tagged(LayoutViewTestTags.detachedToolWindow(runtime)).assertIsDisplayed()
            test.onNode(hasTestTag(RuntimeViewTestTags.run(1)) and hasAnyAncestor(hasTestTag(LayoutViewTestTags.detachedToolWindow(runtime))))
                .assertIsDisplayed()
            assertEquals(1L, workbench.state.runtime.value?.selected?.value?.id, "the selected run carries over")
            assertTrue(assertNotNull(windows.last).title.startsWith("Runtime - "), "the title names the tool window first")

            tagged(LayoutViewTestTags.placeButton(runtime)).performClick()
            settle()

            test.onNodeWithTag(LayoutViewTestTags.detachedToolWindow(runtime)).assertDoesNotExist()
            tagged(WorkbenchTestTags.toolWindow(runtime)).assertIsDisplayed()
            test.onNode(hasTestTag(RuntimeViewTestTags.run(1)) and hasAnyAncestor(hasTestTag(WorkbenchTestTags.toolWindow(runtime))))
                .assertIsDisplayed()
        }
    }

    @Test
    fun `closing the detached window docks the tool window, and its bounds are kept for the next time`() {
        val windows = InPlaceWindows()
        workbenchUiTest(dir, detachedWindows = windows) {
            openLamp()
            workbench.state.layout.toggleDetached(runtime)
            settle()
            val moved = WindowBounds(x = 900, y = 40, width = 700, height = 500)
            assertNotNull(windows.last).onBoundsChange(moved)
            settle()

            assertNotNull(windows.last).onCloseRequest()
            settle()

            test.onNodeWithTag(LayoutViewTestTags.detachedToolWindow(runtime)).assertDoesNotExist()
            tagged(WorkbenchTestTags.toolWindow(runtime)).assertIsDisplayed()

            workbench.state.layout.toggleDetached(runtime)
            settle()
            assertEquals(moved, assertNotNull(windows.last).initialBounds)
        }
    }

    @Test
    fun `the status bar brings the detached window to the front instead of docking a second copy`() {
        val windows = InPlaceWindows()
        workbenchUiTest(dir, detachedWindows = windows) {
            openLamp()
            workbench.state.layout.toggleDetached(runtime)
            settle()
            val before = assertNotNull(windows.last).raiseRequests

            tagged(StatusBarTestTags.item(RUNTIME_SEGMENT)).performClick()
            settle()

            assertEquals(before + 1, assertNotNull(windows.last).raiseRequests)
            test.onNodeWithTag(WorkbenchTestTags.toolWindow(runtime)).assertDoesNotExist()
            tagged(LayoutViewTestTags.detachedToolWindow(runtime)).assertIsDisplayed()
        }
    }

    /** Copies the lamp into the workspace and opens it. */
    private suspend fun WorkbenchUi.openLamp() {
        SynthesizedModels.copyTo(root.resolve(SynthesizedModel.LAMP.folder), SynthesizedModel.LAMP, DescriptorTiming.EVENT_SCENARIOS)
        workbench.open(root)
        settle()
    }

    /** Runs the lamp without bridges or capture and lets its scripted session run. */
    private fun WorkbenchUi.run() {
        assertNotNull(workbench.state.runtime.value).run(lamp)
        settle()
        workbench.state.dialogs.respond(DialogResponse.RunWith(emptySet(), capture = false))
        settle()
        workbench.scriptedSessions.launched.single().stateFlow.value = SessionState.Running(EnvironmentKey("env-1"))
        settle()
    }

    /** A [DetachedWindowHost] that draws the window's content in place and keeps the last [DetachedWindow] it was given. */
    private class InPlaceWindows : DetachedWindowHost {
        var last: DetachedWindow? = null

        @Composable
        override fun Window(window: DetachedWindow, content: @Composable () -> Unit) {
            SideEffect { last = window }
            Box(Modifier.fillMaxSize()) { content() }
        }
    }

    private companion object {
        /** The status bar segment of the runtime. */
        const val RUNTIME_SEGMENT = "runtime"
    }
}
