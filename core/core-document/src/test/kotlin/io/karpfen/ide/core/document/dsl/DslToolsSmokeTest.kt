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

import io.karpfen.dsl.meta.AssociationType
import io.karpfen.dsl.reader.kmeta.KmetaReader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves that karpfen-dsl-tools resolves (composite build or artifact) and reads KMeta. */
class DslToolsSmokeTest {

    /** By the KMeta convention the last type of a metamodel is its root. */
    @Test
    fun `reads a tiny metamodel with karpfen-dsl-tools`() {
        val read = KmetaReader().read(
            """
            type "Wheel" "A wheel" {
                prop("radius", "number")
            }

            type "Car" "A car with one wheel" {
                prop("name", "string")
                has("wheel", "Wheel")
            }
            """,
        )

        assertEquals(emptyList(), read.diagnostics)
        val metamodel = read.model!!
        assertEquals("Car", metamodel.rootClass.name)
        assertEquals(setOf("Wheel", "Car"), metamodel.getClassTypes())
        val wheel = metamodel.rootClass.getObjectProperty("wheel")
        assertEquals(AssociationType.EMBEDDED, wheel.reference.associationType)
        assertEquals("Wheel", wheel.reference.classTypeName)
    }
}
