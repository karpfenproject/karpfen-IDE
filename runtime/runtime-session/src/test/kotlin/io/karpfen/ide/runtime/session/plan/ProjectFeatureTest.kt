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
import kotlin.test.assertTrue

/**
 * Covers [ProjectFeature]: TrueParallel switches on the runtime feature of the same name and needs splits and joins.
 * Its id differs from every id of a language feature, so both share the keys of the project file.
 */
class ProjectFeatureTest {

    @Test
    fun `TrueParallel switches on its runtime feature only while splits and joins are on`() {
        val parallel = setOf(ProjectFeature.TRUE_PARALLEL)

        assertEquals(listOf(FeatureName.TRUE_PARALLEL), ProjectFeature.runtimeFeaturesOf(parallel, setOf(KstatesFeature.PARALLEL)))
        assertEquals(emptyList(), ProjectFeature.runtimeFeaturesOf(parallel, setOf(KstatesFeature.HISTORY)))
        assertEquals(emptyList(), ProjectFeature.runtimeFeaturesOf(emptySet(), KstatesFeature.ALL))
        assertEquals(parallel, ProjectFeature.allowedBy(parallel, setOf(KstatesFeature.PARALLEL)))
        assertEquals(emptySet(), ProjectFeature.allowedBy(parallel, emptySet()))
    }

    @Test
    fun `the ids are found back and never clash with the ids of the language features`() {
        assertEquals(ProjectFeature.TRUE_PARALLEL, ProjectFeature.byId("trueParallel"))
        assertNull(ProjectFeature.byId("parallel"))
        assertTrue(ProjectFeature.entries.none { feature -> KstatesFeature.entries.any { it.id == feature.id } })
    }
}
