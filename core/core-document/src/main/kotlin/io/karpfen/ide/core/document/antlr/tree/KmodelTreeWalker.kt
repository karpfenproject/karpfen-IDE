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
package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kmodel.KmodelParser
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind

/**
 * Walks a KModel tree and records what it declares and references.
 * - `make object "key":"T" { }` becomes an OBJECT declaration with an OBJECT_NAME span, plus a type reference to T.
 *   Its type name and outline detail are T. Its container is the outline name of the enclosing object, or null for the
 *   root. An object whose key is empty, `_` or missing is anonymous. It is declared with the empty name, its name range
 *   is still the key, and the outline shows it as `:T`.
 * - In `prop("k") -> "v"` the key becomes a PROPERTY_KEY reference. In `has("k") -> make object` and
 *   `knows("k") -> "obj"` the key becomes a RELATION_KEY reference. Each key reference is qualified with the type of
 *   the enclosing object and gets a PROPERTY_NAME span. The target `"obj"` of `knows` becomes an OBJECT reference with
 *   an OBJECT_NAME span.
 * - These statements are not declarations. The outline shows them as PROPERTY_ASSIGNMENT nodes with the value as
 *   detail, HAS_ASSIGNMENT nodes with the nested object as child, and KNOWS_ASSIGNMENT nodes with the target key as
 *   detail.
 */
internal class KmodelTreeWalker(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    /** The outline of [file], which is the root object if the text has one. */
    fun walk(file: KmodelParser.Kmodel_fileContext): List<OutlineNode> =
        listOfNotNull(file.make_object_block()?.let { block(it, container = null) })

    /** Records the object of [ctx] inside the object named [container] and returns its outline node. */
    private fun block(ctx: KmodelParser.Make_object_blockContext, container: String?): OutlineNode {
        val signature = tree.quotedStrings(ctx.object_signature(), KmodelParser.STRING)
        val key = signature.getOrNull(0)
        val type = signature.getOrNull(1)
        collector.typeReference(type)
        val declaredName = key?.value?.takeUnless { it == ANONYMOUS_KEY }.orEmpty()
        val outlineName = declaredName.ifEmpty { ":${type?.value.orEmpty()}" }
        val statements = ctx.statement().mapNotNull { statement(it, type?.value, outlineName) }
        val node = outlineNode(SymbolKind.OBJECT, outlineName, type?.value, tree.rangeOf(ctx), tree.selectionOf(key, ctx), statements)
        if (declaredName.isNotEmpty()) collector.span(key?.inner, SemanticKind.OBJECT_NAME)
        collector.declare(SymbolKind.OBJECT, declaredName, node.selectionRange, node.range, container, type?.value)
        return node
    }

    /** The outline node of a `prop`, `has` or `knows` statement of an object. Null for a statement of another kind. */
    private fun statement(ctx: KmodelParser.StatementContext, objectType: String?, objectName: String): OutlineNode? {
        ctx.prop_statement()?.let { return prop(it, objectType) }
        ctx.has_statement()?.let { return has(it, objectType, objectName) }
        ctx.knows_statement()?.let { return knows(it, objectType) }
        return null
    }

    private fun prop(ctx: KmodelParser.Prop_statementContext, objectType: String?): OutlineNode {
        val strings = tree.quotedStrings(ctx, KmodelParser.STRING)
        val key = strings.getOrNull(0)
        collector.reference(ReferenceKind.PROPERTY_KEY, key, objectType, SemanticKind.PROPERTY_NAME)
        return outlineNode(
            SymbolKind.PROPERTY_ASSIGNMENT, key?.value.orEmpty(), strings.getOrNull(1)?.value, tree.rangeOf(ctx), tree.selectionOf(key, ctx),
        )
    }

    private fun has(ctx: KmodelParser.Has_statementContext, objectType: String?, objectName: String): OutlineNode {
        val key = tree.quoted(tree.terminal(ctx, KmodelParser.STRING))
        collector.reference(ReferenceKind.RELATION_KEY, key, objectType, SemanticKind.PROPERTY_NAME)
        val nested = listOfNotNull(ctx.make_object_block()?.let { block(it, container = objectName) })
        return outlineNode(SymbolKind.HAS_ASSIGNMENT, key?.value.orEmpty(), null, tree.rangeOf(ctx), tree.selectionOf(key, ctx), nested)
    }

    private fun knows(ctx: KmodelParser.Knows_statementContext, objectType: String?): OutlineNode {
        val strings = tree.quotedStrings(ctx, KmodelParser.STRING)
        val key = strings.getOrNull(0)
        val target = strings.getOrNull(1)
        collector.reference(ReferenceKind.RELATION_KEY, key, objectType, SemanticKind.PROPERTY_NAME)
        collector.reference(ReferenceKind.OBJECT, target, span = SemanticKind.OBJECT_NAME)
        return outlineNode(SymbolKind.KNOWS_ASSIGNMENT, key?.value.orEmpty(), target?.value, tree.rangeOf(ctx), tree.selectionOf(key, ctx))
    }

    private companion object {
        /** The key that dsl-tools treats as "no key", besides the empty one. */
        const val ANONYMOUS_KEY = "_"
    }
}
