package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.dsl.textual.KmetaDSLConverter
import io.karpfen.dsl.meta.AssociationType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves that karpfen-dsl-tools resolves (composite build or artifact) and parses KMeta. */
class DslToolsSmokeTest {

    /** By the KMeta convention the last type of a metamodel is its root. */
    @Test
    fun `parses a tiny metamodel with karpfen-dsl-tools`() {
        val metamodel = KmetaDSLConverter.parseKmetaString(
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

        assertEquals("Car", metamodel.rootClass.name)
        assertEquals(setOf("Wheel", "Car"), metamodel.getClassTypes())
        val wheel = metamodel.rootClass.getObjectProperty("wheel")
        assertEquals(AssociationType.EMBEDDED, wheel.reference.associationType)
        assertEquals("Wheel", wheel.reference.classTypeName)
    }
}
