package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind
import kmodel.KmodelParser

/**
 * Walks a KModel tree (spec §3.3):
 * - `make object "key":"T" { … }` → OBJECT declaration (type name and outline detail T; container: the enclosing
 *   object's outline name, null for the root) with an OBJECT_NAME span, plus a type reference to T. An object whose key
 *   is empty, `_` or missing is anonymous: declared with the empty name (its name range is still the key) and shown as
 *   `:T` in the outline.
 * - `prop("k") -> "v"` → PROPERTY_KEY reference, `has("k") -> …` and `knows("k") -> …` → RELATION_KEY references, each
 *   qualified with the enclosing object's type and with a PROPERTY_NAME span; `knows(…) -> "obj"` → OBJECT reference with
 *   an OBJECT_NAME span. They appear in the outline as PROPERTY_ASSIGNMENT (detail: the value), HAS_ASSIGNMENT (child:
 *   the nested object) and KNOWS_ASSIGNMENT (detail: the target key) nodes; they are not declarations.
 */
internal class KmodelTreeWalker(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    /** The outline of [file]: the root object, if the text has one. */
    fun walk(file: KmodelParser.Kmodel_fileContext): List<OutlineNode> =
        listOfNotNull(file.make_object_block()?.let { block(it, container = null) })

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
        /** The key dsl-tools treats as "no key" besides the empty one. */
        const val ANONYMOUS_KEY = "_"
    }
}
