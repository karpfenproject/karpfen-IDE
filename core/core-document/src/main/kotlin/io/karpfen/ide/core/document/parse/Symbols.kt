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

/**
 * A declared symbol.
 *
 * @property kind what the symbol is.
 * @property name the declared name without quotes. It is empty for anonymous objects.
 * @property nameRange the name inside the quotes. It may be empty for `""`, and it lies within [range].
 * @property range the whole declaring construct, such as the full `STATE "s" { ... }`.
 * @property container the name of the enclosing symbol where the language nests declarations, or null at top level.
 *   For a SIMPLE_PROPERTY, HAS_RELATION or KNOWS_RELATION it is the type. For an OBJECT it is the outline name of the
 *   enclosing object. That name is the key of the object, or `:T` for an anonymous object. The root object has null.
 *   For a STATE it is the parent state. For a MACRO_PARAMETER it is the macro. For a BINDING it is the state whose
 *   action block contains it.
 * @property typeName the declared type as written, without quotes, where the construct names one. For an OBJECT it is
 *   its type, such as `T` of `"key":"T"`. For a SIMPLE_PROPERTY it is the primitive, such as `number`. For a
 *   HAS_RELATION or KNOWS_RELATION it is the target, such as `Vector` or `list(Obstacle)`. For a MACRO_PARAMETER it is
 *   the parameter type, such as `Vector`, `number` or `list(Obstacle)`. For a MACRO it is its RETURNS expression, such
 *   as `number` or `reference(Obstacle)`. For a STATEMACHINE it is the `ATTACHED TO` type, or the first type after
 *   `ATTACHABLE TO`. It is null for every other kind, and when the type is missing in broken text.
 */
public data class SymbolDeclaration(
    public val kind: SymbolKind,
    public val name: String,
    public val nameRange: TextRange,
    public val range: TextRange,
    public val container: String?,
    public val typeName: String? = null,
) {
    init {
        require(nameRange in range) { "nameRange $nameRange must lie within range $range" }
    }

    /**
     * True for the declaration of an attachable state machine, `STATEMACHINE "name" ATTACHABLE TO ...`, which is
     * named after its name string. The declaration of an attached state machine is named [ATTACHED_MACHINE_NAME]
     * instead, because that form has no name.
     */
    public val isAttachableStateMachine: Boolean
        get() = kind == SymbolKind.STATEMACHINE && name != ATTACHED_MACHINE_NAME

    /** The names shared by the declarations. */
    public companion object {
        /** The name of the STATEMACHINE declaration of an attached state machine, its keyword `STATEMACHINE`. */
        public const val ATTACHED_MACHINE_NAME: String = "STATEMACHINE"
    }
}

/**
 * A reference to a symbol by name. Resolution happens in core-lang.
 *
 * @property kind what the name refers to.
 * @property name the referenced name without quotes. For [ReferenceKind.MODEL_PATH] it is the whole path with blanks
 *   removed, such as `a->b->c`. This also holds for `$( a -> b )` in EVAL bodies.
 * @property range the name inside the quotes. In EVAL bodies it is the path inside `$( ... )` without surrounding
 *   blanks, or the name after `@`.
 * @property qualifier the context needed to resolve the name, or null where none applies. For a KModel PROPERTY_KEY or
 *   RELATION_KEY it is the type name of the enclosing object. A KStates RELATION_KEY is the relation argument of
 *   `SETOBJ` or `APPENDOBJ`. Its qualifier is the source model path, which is the first argument, such as
 *   `self->room`. The type of that path declares the relation. For an EVENT_NAME the qualifier is the event domain.
 *   Every other kind has null.
 */
public data class SymbolReference(
    public val kind: ReferenceKind,
    public val name: String,
    public val range: TextRange,
    public val qualifier: String?,
)

/** The kind of a [FoldingRange]. */
public enum class FoldingKind {
    /** A brace block spanning more than one line. */
    BLOCK,

    /** The body of an EVAL block. */
    EMBEDDED_CODE,

    /** A run of at least two consecutive comment lines. */
    COMMENT,
}

/**
 * A foldable region.
 *
 * @property range the region. It starts on one line and ends on a later one.
 * @property kind what the region holds.
 */
public data class FoldingRange(public val range: TextRange, public val kind: FoldingKind)
