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
@file:OptIn(ExperimentalSerializationApi::class)

package io.karpfen.ide.core.document.environment

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

/**
 * Derives the [ValueShape]s of a descriptor from the serial descriptor of a `@Serializable` class, so the class is the
 * one declaration of the shape. [EnvironmentDescriptor] is that class.
 *
 * A class becomes a [ValueShape.Record] with one key per property. The key has the serial name of the property. It is
 * required when the property has no default value and nullable when the property type is. Its [KeyDoc] documents it.
 * A list becomes a [ValueShape.ListOf] whose items take the annotations of the list's property. A string becomes what
 * the annotations of its property say it names, through [NamesFile], [NamesObject] or [NamesEventDomain]. Otherwise it
 * becomes [ValueShape.Text] with the [Suggests] values. A 64-bit integer becomes a [ValueShape.WholeNumber] with the
 * [SuggestsNumbers] values. The object has no state and no side effects.
 */
internal object SerialShapes {

    /**
     * The record of the class that [descriptor] describes.
     *
     * @throws IllegalArgumentException if [descriptor] is no class, if a property lacks its [KeyDoc], or if a property
     *   has a kind that a descriptor key cannot have. These are programming errors of the descriptor class.
     */
    fun recordOf(descriptor: SerialDescriptor): ValueShape.Record {
        require(descriptor.kind == StructureKind.CLASS) { "${descriptor.serialName} is no class" }
        return ValueShape.Record((0 until descriptor.elementsCount).map { index -> keyOf(descriptor, index) })
    }

    /** The key of the property at [index] of the class [owner]. */
    private fun keyOf(owner: SerialDescriptor, index: Int): DescriptorKey {
        val name = owner.getElementName(index)
        val annotations = owner.getElementAnnotations(index)
        val documentation = annotations.filterIsInstance<KeyDoc>().singleOrNull()?.text
        requireNotNull(documentation) { "the key '$name' of ${owner.serialName} has no KeyDoc" }
        val element = owner.getElementDescriptor(index)
        return DescriptorKey(
            name = name,
            shape = shapeOf(element, annotations, "${owner.serialName}.$name"),
            isRequired = !owner.isElementOptional(index),
            isNullable = element.isNullable,
            documentation = documentation,
        )
    }

    /** The shape of a value that [descriptor] describes, under a property with [annotations], at [location]. */
    private fun shapeOf(descriptor: SerialDescriptor, annotations: List<Annotation>, location: String): ValueShape =
        when (descriptor.kind) {
            StructureKind.CLASS -> recordOf(descriptor)
            StructureKind.LIST -> ValueShape.ListOf(shapeOf(descriptor.getElementDescriptor(0), annotations, "$location[]"))
            PrimitiveKind.STRING -> stringShapeOf(annotations)
            PrimitiveKind.LONG -> ValueShape.WholeNumber(annotations.filterIsInstance<SuggestsNumbers>().flatMap { it.values.toList() })
            else -> throw IllegalArgumentException("$location has the kind ${descriptor.kind}, which no descriptor key has")
        }

    /** The shape of a string under a property with [annotations]. It is what the string names, or else text. */
    private fun stringShapeOf(annotations: List<Annotation>): ValueShape {
        val file = annotations.filterIsInstance<NamesFile>().firstOrNull()
        return when {
            file != null -> ValueShape.FilePath(file.role)
            annotations.any { it is NamesObject } -> ValueShape.ObjectKey
            annotations.any { it is NamesEventDomain } -> ValueShape.EventDomain
            else -> ValueShape.Text(annotations.filterIsInstance<Suggests>().flatMap { it.values.toList() })
        }
    }
}
