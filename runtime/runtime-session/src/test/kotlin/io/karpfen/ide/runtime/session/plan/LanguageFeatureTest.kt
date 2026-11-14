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

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [LanguageFeature]: histories switch on `History`, tick limits `TickLimit` and breakpoints `Breakpoint`. Macros,
 * splits and joins and dynamic attachment switch nothing on. The runtime features are listed once each, in the order of the language
 * features.
 */
class LanguageFeatureTest {

    @Test
    fun `histories, tick limits and breakpoints switch on their runtime features`() {
        assertEquals(FeatureName.HISTORY, LanguageFeature.runtimeFeatureOf(KstatesFeature.HISTORY))
        assertEquals(FeatureName.TICK_LIMIT, LanguageFeature.runtimeFeatureOf(KstatesFeature.TICK_LIMIT))
        assertEquals(FeatureName.BREAKPOINT, LanguageFeature.runtimeFeatureOf(KstatesFeature.BREAKPOINTS))
    }

    @Test
    fun `macros, splits and joins and dynamic attachment switch nothing on`() {
        listOf(KstatesFeature.MACROS, KstatesFeature.PARALLEL, KstatesFeature.ATTACHMENT).forEach {
            assertNull(LanguageFeature.runtimeFeatureOf(it), "$it")
        }
        assertEquals(emptyList(), LanguageFeature.runtimeFeaturesOf(setOf(KstatesFeature.PARALLEL, KstatesFeature.MACROS)))
        assertEquals(emptyList(), LanguageFeature.runtimeFeaturesOf(emptySet()))
    }

    @Test
    fun `the runtime features of several language features are listed in the order they are switched on`() {
        val all = KstatesFeature.entries.toSet()

        assertEquals(listOf(FeatureName.HISTORY, FeatureName.TICK_LIMIT, FeatureName.BREAKPOINT), LanguageFeature.runtimeFeaturesOf(all))
        assertEquals(listOf(FeatureName.TICK_LIMIT), LanguageFeature.runtimeFeaturesOf(setOf(KstatesFeature.TICK_LIMIT, KstatesFeature.PARALLEL)))
    }
}
