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
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName

/**
 * A language feature of the runtime: a feature that gives meaning to words of a state machine text. The runtime reads
 * these words in every environment but applies them only where the feature is on. Each entry knows the runtime's name
 * of the feature and which states use it, so a deployment switches on exactly the features its machines need.
 *
 * The entries are declared in the order a deployment switches them on.
 *
 * @property featureName the runtime's name of the feature.
 */
internal enum class LanguageFeature(val featureName: FeatureName) {
    /** `SHALLOW HISTORY` and `DEEP HISTORY`, used by a state whose history is not [History.NONE]. */
    HISTORY(FeatureName.HISTORY) {
        override fun isUsedBy(state: State): Boolean = state.history != History.NONE
    },

    /** The `DELAY` and the `TIMEOUT` of a `TICK LIMIT` block, used by a state that has either of them. */
    TICK_LIMIT(FeatureName.TICK_LIMIT) {
        override fun isUsedBy(state: State): Boolean = state.delay != null || state.timeout != null
    },
    ;

    /** True if [state] itself uses this feature. Its nested states are not looked at. */
    abstract fun isUsedBy(state: State): Boolean

    /** True if a state of [machine], at any depth, uses this feature. */
    fun isUsedBy(machine: StateMachine): Boolean = machine.states.any { isUsedAnywhereIn(it) }

    /** True if [state] or one of the states nested in it, at any depth, uses this feature. */
    private fun isUsedAnywhereIn(state: State): Boolean = isUsedBy(state) || state.innerStates.any { isUsedAnywhereIn(it) }

    companion object {
        /**
         * The names of the language features that [machines] use, in the order of the entries, each once. It is empty
         * if no machine uses a language feature.
         */
        fun requiredBy(machines: Collection<StateMachine>): List<FeatureName> =
            entries.filter { feature -> machines.any { feature.isUsedBy(it) } }.map { it.featureName }
    }
}
