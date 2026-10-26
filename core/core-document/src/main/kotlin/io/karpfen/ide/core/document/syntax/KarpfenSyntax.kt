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
package io.karpfen.ide.core.document.syntax

import io.karpfen.dsl.meta.SimplePropertyType
import io.karpfen.dsl.states.actions.ActionOperationType

/**
 * The lexical facts of the karpfen languages that several layers rely on, declared once (one source per fact): the
 * tokenizers, tree walkers and EVAL scanner of this module, the language services (core-lang) and the editor
 * (ui-editor) read them here, so e.g. the completion's replace range and the editor's word rule always agree.
 * `KarpfenSyntaxTest` checks them against the generated lexers, and the architecture test `LexicalFactsTest` rejects a
 * second declaration of any of them elsewhere, the comment markers included. Where dsl-tools publishes a fact in its
 * semantic model, such as the primitive types and the action operations with their operand counts, it is taken from
 * there, so the IDE names it exactly as dsl-tools reads it. Undo coalescing keeps its own Unicode word rule on purpose:
 * it describes text, not identifiers.
 */
public object KarpfenSyntax {

    /**
     * The KMeta primitive property types as written in the files: the keywords of dsl-tools' [SimplePropertyType], which
     * holds them, so the IDE names them exactly as dsl-tools reads them. KStates uses the same names in TAKES/RETURNS
     * types.
     */
    public val PRIMITIVE_TYPES: Set<String> = SimplePropertyType.entries.mapTo(LinkedHashSet()) { it.keyword }

    /**
     * The KStates action operations, by their upper-case names (the grammar accepts both cases), in the order of
     * dsl-tools' [ActionOperationType]: every operation but `EVENT`, which is also the keyword of an event clause in
     * conditions and therefore a keyword of its own.
     */
    public val KSTATES_OPERATIONS: Set<String> = operationsWith { true }

    /**
     * The KStates operations whose second argument is a relation of the type their first argument (a path) names: the
     * operations of three operands, a source, a relation and a target.
     */
    public val KSTATES_RELATION_OPERATIONS: Set<String> = operationsWith { it.operandCount == 3 }

    /**
     * The KStates operations that take a path only (`DROPLIST("p")`), the operations of one operand; the others take a
     * value after their path.
     */
    public val KSTATES_UNARY_OPERATIONS: Set<String> = operationsWith { it.operandCount == 1 }

    /**
     * The line comment of the modeling languages; inside EVAL bodies it is still a karpfen comment, which the Python
     * code never sees. The language supports of the modeling languages hand it out as their `lineComment`.
     */
    public const val LINE_COMMENT: String = "//"

    /** The line comment of the Python code in KStates EVAL bodies, which comments only inside such a body. */
    public const val PYTHON_LINE_COMMENT: String = "#"

    /** The separator of model-path segments (`self -> room -> robot`). */
    public const val MODEL_PATH_SEPARATOR: String = "->"

    /** The KStates keywords that open an action block (`EVENT` is an operation there), by their upper-case names. */
    public val KSTATES_ACTION_BLOCKS: Set<String> = setOf("ENTRY", "DO", "IF", "WITH")

    /** The KStates keyword that opens a condition block (`EVENT` is a condition keyword there). */
    public const val KSTATES_CONDITION_BLOCK: String = "CONDITION"

    /** The KStates keywords that open every other block (structure, macro definitions, EVAL bodies). */
    public val KSTATES_STRUCTURE_BLOCKS: Set<String> = setOf(
        "STATEMACHINE", "STATES", "INITIAL", "STATE", "TRANSITIONS", "TRANSITION", "SPLIT", "JOIN", "MACROS", "MACRO",
        "DEFINITION", "EVAL",
    )

    /**
     * True for the characters of identifiers and model-path segments, `[A-Za-z0-9_]` (the lexers' identifier rule; a
     * digit only cannot start one).
     */
    public fun isIdentifierChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'

    /** True for the blanks inside a line, space and tab; a model path may contain them around its segments. */
    public fun isBlank(char: Char): Boolean = char == ' ' || char == '\t'

    /** True if a [MODEL_PATH_SEPARATOR] starts at [index] of [text] and ends before [limit]. */
    public fun isPathSeparatorAt(text: CharSequence, index: Int, limit: Int = text.length): Boolean =
        index >= 0 && index + MODEL_PATH_SEPARATOR.length <= minOf(limit, text.length) &&
            text.startsWith(MODEL_PATH_SEPARATOR, index)

    /**
     * A model path as `SymbolReference.name` spells it and the path resolvers read it: without blanks (`a -> b` →
     * `a->b`), so the tree walkers, the EVAL scanner and core-lang name every path alike.
     */
    public fun normalizeModelPath(path: String): String = path.filterNot(::isBlank)

    /**
     * The names of the [KSTATES_OPERATIONS] among dsl-tools' [ActionOperationType] that [accepts], with
     * [ActionOperationType.operandCount] as the number of operands an operation is written with.
     */
    private fun operationsWith(accepts: (ActionOperationType) -> Boolean): Set<String> =
        ActionOperationType.entries
            .filter { it != ActionOperationType.EVENT && accepts(it) }
            .mapTo(LinkedHashSet()) { it.name }
}
