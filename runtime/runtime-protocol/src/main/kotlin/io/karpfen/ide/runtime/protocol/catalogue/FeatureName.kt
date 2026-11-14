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
package io.karpfen.ide.runtime.protocol.catalogue

/**
 * The name of a feature of the runtime, such as `History` or `TickLimit`. A feature is a part of the engine that an
 * environment switches on and off while it exists. The runtime knows its features by these names, which travel as the
 * query parameter `featureName` and in the lists the runtime answers. Those lists separate the names with
 * [WireNames.FEATURE_SEPARATOR], so a name holds no such separator. It holds no whitespace either, because the
 * runtime compares names exactly.
 *
 * @property value the name as the runtime writes it.
 * @throws IllegalArgumentException if [value] is blank or holds the separator or whitespace.
 */
@JvmInline
public value class FeatureName(public val value: String) {
    init {
        require(value.isNotBlank()) { "a feature name must not be blank" }
        require(WireNames.FEATURE_SEPARATOR !in value && value.none { it.isWhitespace() }) {
            "a feature name must not hold '${WireNames.FEATURE_SEPARATOR}' or whitespace: '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        /**
         * The language feature that applies the `SHALLOW HISTORY` and `DEEP HISTORY` of states. Without it the runtime
         * reads a history and ignores it.
         */
        public val HISTORY: FeatureName = FeatureName("History")

        /**
         * The language feature that applies the `DELAY` and the `TIMEOUT` of the `TICK LIMIT` blocks of states. Without
         * it the runtime reads a tick limit and ignores it.
         */
        public val TICK_LIMIT: FeatureName = FeatureName("TickLimit")

        /**
         * The feature that halts the engine at the `BREAKPOINT` blocks of states and transitions and tells where it
         * halted. Switching it on switches [TICK_BY_TICK] on as well, which runs a halted engine on.
         */
        public val BREAKPOINT: FeatureName = FeatureName("Breakpoint")

        /** The feature that pauses the engine, resumes it and runs it on tick by tick. */
        public val TICK_BY_TICK: FeatureName = FeatureName("TickByTick")

        /**
         * The feature that steps the branches of a split state machine at the same time, each branch on a thread of
         * its own. Without it the engine steps the branches one after the other on one thread. It is no language
         * feature: a run switches it on when the person running the project asks for it.
         */
        public val TRUE_PARALLEL: FeatureName = FeatureName("TrueParallel")
    }
}
