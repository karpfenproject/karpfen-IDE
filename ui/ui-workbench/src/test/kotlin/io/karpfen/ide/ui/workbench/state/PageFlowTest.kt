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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.tabs.EditorViewMode
import io.karpfen.ide.ui.workbench.testing.TestWorkbench
import io.karpfen.ide.ui.workbench.testing.withWorkbench
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the steps between the pages of a state machine: from a container state to the diagram of its fragment, which
 * is found by its name, and back to a file that uses the fragment. The diagram shows the way it showed before.
 */
class PageFlowTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.resolve("ws").toAbsolutePath().normalize()
    private val lamp: Path get() = root.resolve("lamp.kstates")
    private val fragment: Path get() = root.resolve("parts/bulb_test.frag.kstates")

    private fun scenario(block: suspend TestWorkbench.() -> Unit) =
        runTest(timeout = 60.seconds) {
            TestWorkbench.write(
                lamp,
                """
                STATEMACHINE ATTACHED TO "Lamp" {
                    STATES {
                        INITIAL STATE "off" { }
                        CONTAINER STATE "self test" USE "bulb_test" { }
                    }
                    TRANSITIONS {
                        TRANSITION "off" -> "self test" { }
                    }
                }
                """.trimIndent(),
            )
            TestWorkbench.write(
                fragment,
                """
                STATEMACHINE "bulb_test" FRAGMENT {
                    STATES {
                        INITIAL STATE "powered" { }
                        FINAL STATE "passed" { }
                    }
                    TRANSITIONS {
                        TRANSITION "powered" -> "passed" { }
                    }
                }
                """.trimIndent(),
            )
            withWorkbench(dir.resolve("settings")) {
                open(root)
                block()
            }
        }

    @Test
    fun `the page of a container state opens the fragment with its diagram shown as before`() = scenario {
        openEditor(lamp)
        state.tabs.setViewMode(lamp, EditorViewMode.SPLIT)

        state.openPage(lamp, "bulb_test").joinSettled()

        assertEquals(fragment, state.tabs.active.value)
        assertEquals(EditorViewMode.SPLIT, state.tabs.viewModes.value[fragment])
    }

    @Test
    fun `a fragment goes back to a file that uses it, and a diagram shown alone stays alone`() = scenario {
        openEditor(fragment)
        state.tabs.setViewMode(fragment, EditorViewMode.DIAGRAM)

        state.openWithDiagram(lamp, fragment).joinSettled()

        assertEquals(lamp, state.tabs.active.value)
        assertEquals(EditorViewMode.DIAGRAM, state.tabs.viewModes.value[lamp])
    }

    @Test
    fun `a fragment that no file declares is reported and nothing opens`() = scenario {
        openEditor(lamp)

        val job = state.openPage(lamp, "wire test")
        val error = answer(DialogRequest.Error::class, DialogResponse.Confirm)
        job.joinSettled()

        assertEquals("Cannot open the page wire test", error.title)
        assertEquals(lamp, state.tabs.active.value)
    }
}
