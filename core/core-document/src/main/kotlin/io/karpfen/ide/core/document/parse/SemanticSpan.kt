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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/** The role of a string or an identifier, as the parse tree shows it. It drives semantic highlighting. */
public enum class SemanticKind {
    /** A declared or referenced type name. */
    TYPE_NAME,

    /** One of the primitive types `number`, `string` and `boolean`. */
    PRIMITIVE_TYPE,

    /** A property or relation key, or a key of an environment descriptor. */
    PROPERTY_NAME,

    /** A KModel object key. */
    OBJECT_NAME,

    /** A state name. */
    STATE_NAME,

    /** A macro name. */
    MACRO_NAME,

    /** An event name. */
    EVENT_NAME,

    /** An event domain. */
    EVENT_DOMAIN,

    /** A model path. */
    MODEL_PATH,

    /** The description string of a KMeta type. */
    DESCRIPTION,

    /** The name that a `WITH` block binds with `AS`. */
    BINDING_NAME,

    /** A file path in an environment descriptor, relative to the descriptor's directory. */
    FILE_PATH,

    /** The name of an attachable state machine, where it is declared and where `behaves after`, `ATTACH` or `EXCHANGE` names it. */
    STATE_MACHINE_NAME,
}

/**
 * A range with a [SemanticKind]. The range of a name lies inside its quotes. The spans of one [ParseOutcome] are
 * sorted by start and do not overlap.
 *
 * @property range the highlighted range.
 * @property kind the role of the text in [range].
 */
public data class SemanticSpan(public val range: TextRange, public val kind: SemanticKind)
