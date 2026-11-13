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

/**
 * The runtime side of the optional language features of KStates. The runtime reads the words of every feature in every
 * environment, but it applies a history only where its feature `History` is on, a tick limit only where `TickLimit`
 * is on and a breakpoint only where `Breakpoint` is on. A deployment therefore switches on the runtime features of the
 * language features that are switched on for the project, whether the machines use them or not. A session whose
 * deployment switched `Breakpoint` on offers a debugger that pauses, resumes and steps the engine.
 *
 * Macros and splits and joins have no runtime feature.
 */
internal object LanguageFeature {

    /** The runtime feature that [feature] switches on, or null if it switches none on. */
    fun runtimeFeatureOf(feature: KstatesFeature): FeatureName? = when (feature) {
        KstatesFeature.HISTORY -> FeatureName.HISTORY
        KstatesFeature.TICK_LIMIT -> FeatureName.TICK_LIMIT
        KstatesFeature.BREAKPOINTS -> FeatureName.BREAKPOINT
        KstatesFeature.MACROS, KstatesFeature.PARALLEL -> null
    }

    /**
     * The runtime features that the switched-on [features] need, each once, in the order of the entries of
     * [KstatesFeature], which is the order a deployment switches them on. It is empty if none of them needs one.
     */
    fun runtimeFeaturesOf(features: Set<KstatesFeature>): List<FeatureName> =
        KstatesFeature.entries.filter { it in features }.mapNotNull(::runtimeFeatureOf).distinct()
}
