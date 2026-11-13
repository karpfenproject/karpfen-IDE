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
package io.karpfen.ide.runtime.session

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.runtime.protocol.catalogue.RuntimeRoute
import io.karpfen.ide.runtime.protocol.conformance.SynthesizedModel
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers the optional language features that need a feature of the runtime, through a session against a real
 * runtime. The crossing has a state with a deep history and a state with a timeout. A project that has histories and
 * tick limits switched on deploys it, the deployment switches the runtime's features History and TickLimit on, and
 * the machine then comes back to the nested state it left and leaves a state by itself. A project that has them
 * switched off is refused before anything reaches the runtime.
 */
abstract class LanguageFeatureScenarios {

    /** The runtime the scenarios deploy to. */
    protected abstract val runtime: SessionUnderTest

    /** The folder the scenarios copy their models into. */
    @TempDir
    lateinit var root: Path

    @Test
    fun `a project with histories and tick limits on switches the runtime's features on, and the machine uses both`() = runtime.run(root) {
        val session = deploy(SynthesizedModel.CROSSING)
        session.awaitStack("crossing", GO)

        val active = call(RuntimeRoute.GET_ACTIVE_FEATURES, "envKey" to session.environment.value)
        assertEquals(setOf("History", "TickLimit"), active.body.split(",").filter { it.isNotEmpty() }.toSet(), diagnostics)

        session.inject("street", "pedestrian")
        session.awaitStack("crossing", WAIT)
        session.inject("street", "service")
        session.awaitStack("crossing", listOf("maintenance"))
        session.awaitModel("the blinking light") { it.mirror.objectOf("crossing").text("light") == "blinking" }

        session.inject("street", "resume")
        session.awaitStack("crossing", WAIT)
        session.awaitModel("the red light of the state the history led back to") { it.mirror.objectOf("crossing").text("light") == "red" }

        session.awaitStack("crossing", GO, within = patience.burst)
        session.awaitModel("the green light after the timeout") { it.mirror.objectOf("crossing").text("light") == "green" }
    }

    @Test
    fun `a project with the features off is refused before anything reaches the runtime`() = runtime.run(root) {
        val descriptor = copy(SynthesizedModel.CROSSING)
        val workspace = open(descriptor, features = KstatesFeature.ALL - KstatesFeature.HISTORY - KstatesFeature.TICK_LIMIT)
        val before = call(RuntimeRoute.LIST_ENVIRONMENTS).body

        val blocked = assertIs<Preparation.Blocked>(sessions.prepare(workspace, descriptor), diagnostics)

        assertEquals(listOf(DeployProblemCodes.CONTEXT_ERRORS), blocked.findings.map { it.code }, "${blocked.findings}")
        val errors = workspace.awaitAnalysis().diagnosticsFor(descriptor.resolveSibling("crossing.kstates"))
        assertEquals(listOf(FEATURE_OFF, FEATURE_OFF), errors.map { it.code }, "one for the history and one for the tick limit: $errors")
        assertEquals(before, call(RuntimeRoute.LIST_ENVIRONMENTS).body, diagnostics)
    }

    private companion object {
        /** The stack of the crossing while the cars go, which is also its initial stack. */
        val GO = listOf("operating", "cars go")

        /** The stack of the crossing while the cars wait. */
        val WAIT = listOf("operating", "cars wait")

        /** The code of a construct of a language feature that is switched off. */
        const val FEATURE_OFF = "kstates.feature-off"
    }
}
