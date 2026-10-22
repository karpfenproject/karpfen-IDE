package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.document.environment.ValueShape
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The typed [EnvironmentDescriptor] has exactly the shape of `EnvironmentSchema.ROOT`, the one home of the descriptor
 * keys: the same keys in the same order at every level, required keys exactly where the class has no default, `null`
 * exactly where the class allows it, and the value kinds the schema promises (strings for paths, keys, domains and
 * text, 64-bit integers for whole numbers, lists and objects). So a descriptor the reader's schema check accepts is one
 * kotlinx.serialization decodes, and the class can never grow a key the schema does not know.
 */
class EnvironmentSchemaAgreementTest {

    @Test
    fun `the typed descriptor has the shape of the schema`() {
        assertAgrees(EnvironmentSchema.ROOT, EnvironmentDescriptor.serializer().descriptor, "the descriptor")
    }

    /** Asserts that [descriptor] is what [shape] describes; [location] names the place in failure messages. */
    private fun assertAgrees(shape: ValueShape, descriptor: SerialDescriptor, location: String) {
        when (shape) {
            is ValueShape.Record -> {
                assertEquals(StructureKind.CLASS, descriptor.kind, location)
                assertEquals(shape.keys.map { it.name }, (0 until descriptor.elementsCount).map(descriptor::getElementName), location)
                shape.keys.forEachIndexed { index, key ->
                    val element = descriptor.getElementDescriptor(index)
                    assertEquals(!key.isRequired, descriptor.isElementOptional(index), "optional: $location.${key.name}")
                    assertEquals(key.isNullable, element.isNullable, "nullable: $location.${key.name}")
                    assertAgrees(key.shape, element, "$location.${key.name}")
                }
            }
            is ValueShape.ListOf -> {
                assertEquals(StructureKind.LIST, descriptor.kind, location)
                assertEquals(false, descriptor.getElementDescriptor(0).isNullable, "items of $location")
                assertAgrees(shape.item, descriptor.getElementDescriptor(0), "$location[]")
            }
            is ValueShape.FilePath, ValueShape.ObjectKey, ValueShape.EventDomain, is ValueShape.Text ->
                assertEquals(PrimitiveKind.STRING, descriptor.kind, location)
            is ValueShape.WholeNumber -> assertEquals(PrimitiveKind.LONG, descriptor.kind, location)
            ValueShape.Flag -> assertEquals(PrimitiveKind.BOOLEAN, descriptor.kind, location)
        }
    }
}
