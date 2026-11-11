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
import kotlinx.serialization.SerialInfo

/**
 * What a key of [EnvironmentDescriptor] means, in a sentence or two. Hover and completion show it as the documentation
 * of the key, [DescriptorKey.documentation].
 *
 * @property text the documentation.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class KeyDoc(public val text: String)

/**
 * The strings of a key of [EnvironmentDescriptor], or of the items of its list, are file paths of [role]. The schema
 * turns this into [ValueShape.FilePath].
 *
 * @property role what the named files are for.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class NamesFile(public val role: DescriptorFileRole)

/**
 * The strings of a key of [EnvironmentDescriptor] name an object of the model by its key. The schema turns this into
 * [ValueShape.ObjectKey].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class NamesObject

/**
 * The strings of a key of [EnvironmentDescriptor], or of the items of its list, name event domains. The schema turns
 * this into [ValueShape.EventDomain].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class NamesEventDomain

/**
 * Typical strings for a key of [EnvironmentDescriptor], or for the items of its list. Completion offers them as
 * [ValueShape.Text.suggestions].
 *
 * @property values the suggested strings, in the order completion lists them.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class Suggests(public vararg val values: String)

/**
 * Typical whole numbers for a key of [EnvironmentDescriptor]. Completion offers them as
 * [ValueShape.WholeNumber.suggestions].
 *
 * @property values the suggested numbers, in the order completion lists them.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class SuggestsNumbers(public vararg val values: Long)
