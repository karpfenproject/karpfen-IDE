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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the planning of a lamp whose state machine is composed of pages: the fragments the descriptor lists become
 * fragment uploads, a fragment the project has but the descriptor does not list blocks the deployment with its name and
 * its user, directly and through another fragment, and a composition that does not flatten blocks with the words of
 * dsl-tools at the state it concerns.
 */
class PageChecksTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    private val lampDescriptor: Path get() = root.resolve("lamp/lamp.kenv.json")
    private val lampMachine: Path get() = root.resolve("lamp/lamp.kstates")

    @Test
    fun `a listed fragment becomes a fragment upload and no definition`() = runTest(timeout = 60.seconds) {
        val workspaces = pagedLamp(listed = listOf("bulb_test.kstates"))

        val plan = workspaces.plan(lampDescriptor).ready()

        assertEquals(emptyList(), plan.findings)
        assertEquals(emptyList(), plan.definitions)
        assertEquals(listOf("bulb_test.kstates"), plan.fragmentUploads.map { it.fileName })
        assertEquals(listOf(null), plan.fragmentUploads.map { it.attachedTo })
        val afterModel = plan.uploads.dropWhile { it.role != UploadRole.MODEL }.drop(1).map { it.role }
        assertEquals(listOf(UploadRole.FRAGMENT, UploadRole.STATE_MACHINE, UploadRole.STATE_MACHINE), afterModel)
    }

    @Test
    fun `a fragment the descriptor does not list blocks at the descriptor with its name and user`() = runTest(timeout = 60.seconds) {
        val workspaces = pagedLamp(listed = emptyList())

        val problem = workspaces.plan(lampDescriptor).blocked().single { it.code == DeployProblemCodes.FRAGMENT_NOT_LISTED }

        assertEquals(Severity.ERROR, problem.severity)
        assertEquals(
            "lamp.kstates uses the fragment 'bulb test' of bulb_test.kstates, but the descriptor does not list it; the runtime " +
                "refuses to activate the environment.",
            problem.message,
        )
        assertEquals(lampDescriptor, problem.location?.file)
    }

    @Test
    fun `a fragment that only a listed fragment uses is named with the page that uses it`() = runTest(timeout = 60.seconds) {
        val workspaces = pagedLamp(listed = listOf("bulb_test.kstates"))
        root.resolve("lamp/bulb_test.kstates").replaceText("INITIAL STATE \"lit\" { }", "INITIAL CONTAINER STATE \"lit\" USE \"wiring\" { }")
        Files.writeString(root.resolve("lamp/wiring.kstates"), WIRING)

        val problem = workspaces.plan(lampDescriptor).blocked().single { it.code == DeployProblemCodes.FRAGMENT_NOT_LISTED }

        assertTrue(
            problem.message.startsWith("bulb_test.kstates, a page of lamp.kstates, uses the fragment 'wiring' of wiring.kstates"),
            problem.message,
        )
    }

    @Test
    fun `a container state that uses an attachable state machine blocks at that state`() = runTest(timeout = 60.seconds) {
        val workspaces = pagedLamp(listed = listOf("bulb_test.kstates"))
        Files.writeString(root.resolve("lamp/bulb_test.kstates"), ATTACHABLE_TEST)

        val findings = workspaces.plan(lampDescriptor).blocked()

        val problem = findings.singleOrNull { it.code == DeployProblemCodes.NOT_FLATTENED } ?: fail("$findings")
        assertEquals(Severity.ERROR, problem.severity)
        assertTrue(problem.message.startsWith("lamp.kstates cannot be flattened, so the runtime refuses"), problem.message)
        assertTrue("'bulb test'" in problem.message, problem.message)
        val location = problem.location
        assertEquals(lampMachine, location?.file)
        assertTrue(assertNotNull(location?.snapshot).substring(assertNotNull(location?.range)).contains("self test"), "$location")
    }

    /**
     * Workspaces with the synthesized lamp, whose state machine gets the container state `self test` that uses the
     * fragment `bulb test` of `bulb_test.kstates`, with the descriptor listing the files [listed] as well.
     */
    private fun TestScope.pagedLamp(listed: List<String>): PlanningWorkspaces {
        val workspaces = PlanningWorkspaces(this, root)
        workspaces.copy(SynthesizedModel.LAMP)
        lampMachine.replaceText("        STATE \"broken\" {", "        CONTAINER STATE \"self test\" USE \"bulb test\" { }\n        STATE \"broken\" {")
        lampMachine.replaceText(
            "        TRANSITION \"off\" -> \"broken\" {",
            "        TRANSITION \"self test\" -> \"off\" {\n            CONDITION { DONE }\n        }\n        TRANSITION \"off\" -> \"broken\" {",
        )
        Files.writeString(root.resolve("lamp/bulb_test.kstates"), BULB_TEST)
        val entries = listed.joinToString("") { "\n    { \"file\": \"$it\" }," }
        lampDescriptor.replaceText("\"stateMachines\": [", "\"stateMachines\": [$entries")
        return workspaces
    }

    private companion object {
        /** The fragment `bulb test`: it starts in `lit` and passes into its final state. */
        const val BULB_TEST = "STATEMACHINE \"bulb test\" FRAGMENT {\n    STATES {\n        INITIAL STATE \"lit\" { }\n" +
            "        FINAL STATE \"passed\" { }\n    }\n    TRANSITIONS {\n        TRANSITION \"lit\" -> \"passed\" { }\n    }\n}\n"

        /** An attachable state machine named like the fragment `bulb test`, which a container state cannot use. */
        const val ATTACHABLE_TEST = "STATEMACHINE \"bulb test\" ATTACHABLE TO \"Lamp\" {\n    STATES {\n        INITIAL STATE \"lit\" { }\n" +
            "    }\n    TRANSITIONS { }\n}\n"

        /** The fragment `wiring`, with one state. */
        const val WIRING = "STATEMACHINE \"wiring\" FRAGMENT {\n    STATES {\n        INITIAL STATE \"checked\" { }\n    }\n" +
            "    TRANSITIONS { }\n}\n"
    }
}
