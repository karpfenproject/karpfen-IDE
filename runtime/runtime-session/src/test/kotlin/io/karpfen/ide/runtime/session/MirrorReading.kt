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
package io.karpfen.ide.runtime.session

import io.karpfen.ide.runtime.session.mirror.MirrorObject
import io.karpfen.ide.runtime.session.mirror.MirrorSnapshot
import io.karpfen.ide.runtime.session.mirror.PropertyValue
import io.karpfen.ide.runtime.session.mirror.ScalarValue
import io.karpfen.ide.runtime.session.observation.ObservedModel
import kotlinx.coroutines.flow.SharedFlow

/** The object [id] of the mirror, failing if it has none. */
internal fun MirrorSnapshot.objectOf(id: String): MirrorObject = checkNotNull(objects[id]) { "the mirror has no object '$id': ${objects.keys}" }

/** The text of the single string property [key], or null if the object has none. */
internal fun MirrorObject.text(key: String): String? =
    ((properties.firstOrNull { it.key == key }?.value as? PropertyValue.Single)?.value as? ScalarValue.Text)?.value

/** The number of the single number property [key], or null if the object has none. */
internal fun MirrorObject.number(key: String): Double? =
    ((properties.firstOrNull { it.key == key }?.value as? PropertyValue.Single)?.value as? ScalarValue.Number)?.value

/** The flag of the single boolean property [key], or null if the object has none. */
internal fun MirrorObject.flag(key: String): Boolean? =
    ((properties.firstOrNull { it.key == key }?.value as? PropertyValue.Single)?.value as? ScalarValue.Flag)?.value

/** The texts of the list property [key], or null if the object has none. */
internal fun MirrorObject.texts(key: String): List<String>? =
    (properties.firstOrNull { it.key == key }?.value as? PropertyValue.Many)?.values?.map { (it as ScalarValue.Text).value }

/** The journal's kept entries, as a collector that starts now gets them before the live ones. */
internal fun RuntimeSession.journal(): List<SessionActivity> = (activity as SharedFlow<SessionActivity>).replayCache

/** The active stack of every machine of the observed model. */
internal val ObservedModel.stacks: Map<String, List<String>>
    get() = states.machines.mapValues { it.value.stack }
