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
 * A feature of a project that is no part of the language: it only switches a feature of the runtime on in every run of
 * the project. It sits next to the optional language features of KStates and is switched the same way, but the
 * language tools never learn it, so it changes nothing about reading, checking or completing a text.
 *
 * Each project feature [requires] a language feature, because it only means something for the constructs of that one.
 * It counts only while that language feature is on as well; [runtimeFeaturesOf] leaves it out otherwise.
 *
 * @property id the name of the feature in the project file, in the style of the ids of the language features.
 * @property displayName the name of the feature in the menu, in sentence case like the language features.
 * @property runtimeFeature the feature of the runtime that a run switches on for it.
 * @property requires the language feature without which this feature has no effect and cannot be switched on.
 */
public enum class ProjectFeature(
    public val id: String,
    public val displayName: String,
    public val runtimeFeature: FeatureName,
    public val requires: KstatesFeature,
) {
    /** Steps the branches of a split at the same time, each on a thread of its own, with the runtime feature TrueParallel. */
    TRUE_PARALLEL("trueParallel", "Parallel branches on threads", FeatureName.TRUE_PARALLEL, KstatesFeature.PARALLEL),
    ;

    public companion object {
        /** The feature whose [id] is [id], or null if there is none. */
        public fun byId(id: String): ProjectFeature? = entries.firstOrNull { it.id == id }

        /**
         * The runtime features of the switched-on [features] whose required language feature is among
         * [languageFeatures], each once, in the order of the entries.
         */
        public fun runtimeFeaturesOf(features: Set<ProjectFeature>, languageFeatures: Set<KstatesFeature>): List<FeatureName> =
            entries.filter { it in features && it.requires in languageFeatures }.map { it.runtimeFeature }

        /** [features] without those whose required language feature is not among [languageFeatures]. */
        public fun allowedBy(features: Set<ProjectFeature>, languageFeatures: Set<KstatesFeature>): Set<ProjectFeature> =
            features.filterTo(LinkedHashSet()) { it.requires in languageFeatures }
    }
}
