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
package io.karpfen.ide.core.document.environment

import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * What a file named in an environment descriptor is for. [EnvironmentSchema] says which key holds a file of which role
 * ([ValueShape.FilePath]); [EnvironmentSchema.keyPathOf] names that key.
 *
 * @property language the language a file in this role is written in: KMeta for the metamodel and the events, KModel for
 *   the model, KStates for a state machine; null for a bridge configuration, which may be any file. The workspace
 *   checks a referenced file against it and converts a context's file only in the language of its role.
 * @property description the role in words, for hovers and messages ("the domain metamodel").
 */
public enum class DescriptorFileRole(public val language: KarpfenLanguage?, public val description: String) {
    /** The domain metamodel. */
    METAMODEL(KarpfenLanguage.KMETA, "the domain metamodel"),

    /** The event metamodel. */
    EVENTS(KarpfenLanguage.KMETA, "the event metamodel"),

    /** The model the runtime starts from. */
    MODEL(KarpfenLanguage.KMODEL, "the model"),

    /** A state machine. */
    STATE_MACHINE(KarpfenLanguage.KSTATES, "a state machine"),

    /** A bridge configuration (`*.kbridge.json`). */
    BRIDGE(null, "a bridge configuration"),
}
