package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/** The role of a string or identifier as determined from the parse tree (spec §3.3); drives semantic highlighting. */
public enum class SemanticKind {
    /** A declared or referenced type name. */
    TYPE_NAME,

    /** `number`, `string`, `boolean`. */
    PRIMITIVE_TYPE,

    /** A property or relation key. */
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

    /** The name of a `WITH … AS "x"` binding. */
    BINDING_NAME,
}

/**
 * A range with a [SemanticKind]. Ranges of names point *inside* the quotes (spec §3.3); spans of one
 * [ParseOutcome] are sorted by start and do not overlap.
 */
public data class SemanticSpan(public val range: TextRange, public val kind: SemanticKind)
