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
