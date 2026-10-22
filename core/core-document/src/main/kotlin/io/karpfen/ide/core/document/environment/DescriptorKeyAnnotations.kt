@file:OptIn(ExperimentalSerializationApi::class)

package io.karpfen.ide.core.document.environment

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * What a key of [EnvironmentDescriptor] means, in a sentence or two: the documentation that hover and completion show
 * for the key ([DescriptorKey.documentation]).
 *
 * @property text the documentation.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class KeyDoc(public val text: String)

/**
 * The strings of a key of [EnvironmentDescriptor], or of the items of its list, are file paths of [role]
 * ([ValueShape.FilePath]).
 *
 * @property role what the named files are for.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class NamesFile(public val role: DescriptorFileRole)

/** The strings of a key of [EnvironmentDescriptor] name an object of the model by its key ([ValueShape.ObjectKey]). */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class NamesObject

/** The strings of a key of [EnvironmentDescriptor], or of the items of its list, name event domains ([ValueShape.EventDomain]). */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class NamesEventDomain

/**
 * Typical strings for a key of [EnvironmentDescriptor], or for the items of its list, which completion offers
 * ([ValueShape.Text.suggestions]).
 *
 * @property values the suggested strings, in the order completion lists them.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class Suggests(public vararg val values: String)

/**
 * Typical whole numbers for a key of [EnvironmentDescriptor], which completion offers
 * ([ValueShape.WholeNumber.suggestions]).
 *
 * @property values the suggested numbers, in the order completion lists them.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class SuggestsNumbers(public vararg val values: Long)
