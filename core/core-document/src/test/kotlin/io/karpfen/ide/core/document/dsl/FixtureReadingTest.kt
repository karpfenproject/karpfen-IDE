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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.SyntheticKstates
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Baseline for reading the examples. Every example context reads without a finding with the dsl-tools readers
 * themselves, and so does a synthetic KStates document. The test calls the readers directly, which only this adapter
 * package may do. If it fails, the fixtures or dsl-tools changed, not the IDE.
 */
class FixtureReadingTest {

    @Test
    fun `every example context reads without findings with dsl-tools`() {
        for (context in Examples.contexts()) {
            val metamodel = KmetaReader().read(context.metamodel!!.text)
            assertEquals(emptyList(), metamodel.diagnostics, "$context")
            val domain = metamodel.model!!
            val reads = listOfNotNull(context.events?.let { it to KmetaReader(domain.types).read(it.text) }) +
                context.models.map { it to KmodelReader(domain).read(it.text) } +
                context.stateMachines.map { it to KstatesReader().read(it.text) }
            reads.forEach { (file, read) -> assertEquals(emptyList(), read.diagnostics, file.path) }
        }
    }

    @Test
    fun `a synthetic KStates document reads without findings with dsl-tools`() {
        val read = KstatesReader().read(SyntheticKstates(120))

        assertEquals(emptyList(), read.diagnostics)
        assertEquals(SyntheticKstates.ATTACHED_TYPE, read.model!!.attachedToClass)
        assertEquals(2, read.model!!.macros.size)
    }
}
