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

import io.karpfen.dsl.states.History
import io.karpfen.dsl.states.State
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.states.actions.ActionBlock
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [LanguageFeature] over state machines built in memory. A history other than `NONE` needs `History`, and a
 * delay or a timeout needs `TickLimit`, at any depth of nesting. Machines without either need nothing. The features of
 * several machines are listed once each, in the order of the entries.
 */
class LanguageFeatureTest {

    @Test
    fun `a machine without histories and tick limits needs no feature`() {
        assertEquals(emptyList(), LanguageFeature.requiredBy(listOf(machine(state("off"), state("on", state("full power"))))))
        assertEquals(emptyList(), LanguageFeature.requiredBy(emptyList()))
    }

    @Test
    fun `a shallow or a deep history needs History, also when it is nested`() {
        listOf(History.SHALLOW, History.DEEP).forEach { history ->
            val nested = machine(state("off"), state("on", state("dimmed", state("low"), history = history)))

            assertEquals(listOf(FeatureName.HISTORY), LanguageFeature.requiredBy(listOf(nested)), "$history")
        }
        assertFalse(LanguageFeature.HISTORY.isUsedBy(state("on", history = History.NONE)))
    }

    @Test
    fun `a delay or a timeout needs TickLimit, also when it is nested`() {
        val delayed = machine(state("off", state("waiting", delay = 2)))
        val timed = machine(state("off"), state("on", timeout = 10, timeoutTo = "off"))

        assertEquals(listOf(FeatureName.TICK_LIMIT), LanguageFeature.requiredBy(listOf(delayed)))
        assertEquals(listOf(FeatureName.TICK_LIMIT), LanguageFeature.requiredBy(listOf(timed)))
        assertTrue(LanguageFeature.TICK_LIMIT.isUsedBy(delayed))
        assertFalse(LanguageFeature.HISTORY.isUsedBy(delayed))
    }

    @Test
    fun `the features of several machines are listed once each in the order they are switched on`() {
        val machines = listOf(
            machine(state("off", delay = 1)),
            machine(state("on", state("inner"), history = History.DEEP)),
            machine(state("idle", timeout = 3, timeoutTo = "idle")),
        )

        assertEquals(listOf(FeatureName.HISTORY, FeatureName.TICK_LIMIT), LanguageFeature.requiredBy(machines))
    }

    /** A machine with the top-level [states]. */
    private fun machine(vararg states: State): StateMachine =
        StateMachine("Lamp", states.toMutableList(), mutableListOf(), mutableListOf())

    /** A state [name] with the nested [inner] states, [history], [delay], [timeout] and [timeoutTo]. */
    private fun state(
        name: String,
        vararg inner: State,
        history: History = History.NONE,
        delay: Long? = null,
        timeout: Long? = null,
        timeoutTo: String? = null,
    ): State =
        State(
            name = name,
            onEntry = ActionBlock(mutableListOf()),
            onDo = ActionBlock(mutableListOf()),
            innerStates = inner.toMutableList(),
            history = history,
            delay = delay,
            timeout = timeout,
            timeoutTo = timeoutTo,
        )
}
