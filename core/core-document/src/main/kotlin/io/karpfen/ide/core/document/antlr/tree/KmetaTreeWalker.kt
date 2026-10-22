package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.dsl.grammar.kmeta.KmetaParser
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind

/**
 * Walks a KMeta tree: `type "N" "D" { … }` → TYPE declaration (outline detail: the description) with a
 * TYPE_NAME span on N and a DESCRIPTION span on D; `prop/has/knows("k", V)` → SIMPLE_PROPERTY / HAS_RELATION /
 * KNOWS_RELATION declarations (container: the type; type name and outline detail: `V` or `list(V)`) with a
 * PROPERTY_NAME span on k and a type reference to V (PRIMITIVE_TYPE for `number`, `string`, `boolean`, else TYPE).
 */
internal class KmetaTreeWalker(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    /** The outline of [file]: one node per type definition, in document order. */
    fun walk(file: KmetaParser.Kmeta_fileContext): List<OutlineNode> = file.type_definition().map(::type)

    /** Records the type defined by [ctx] and returns its outline node, whose children are its rules. */
    private fun type(ctx: KmetaParser.Type_definitionContext): OutlineNode {
        val (name, description) = tree.quotedStrings(ctx, KmetaParser.STRING).let { it.getOrNull(0) to it.getOrNull(1) }
        collector.span(description?.inner, SemanticKind.DESCRIPTION)
        val rules = ctx.rule_list()?.rule_().orEmpty().mapNotNull { rule(it, name?.value.orEmpty()) }
        val node = outlineNode(
            SymbolKind.TYPE, name?.value.orEmpty(), description?.value, tree.rangeOf(ctx), tree.selectionOf(name, ctx), rules,
        )
        collector.declare(SymbolKind.TYPE, name, node.range, container = null, span = SemanticKind.TYPE_NAME)
        return node
    }

    /** Records the property or relation of [ctx] in the type [typeName]; null for a rule of another kind. */
    private fun rule(ctx: KmetaParser.RuleContext, typeName: String): OutlineNode? {
        val (kind, body) = when {
            ctx.prop_rule() != null -> SymbolKind.SIMPLE_PROPERTY to ctx.prop_rule()
            ctx.has_rule() != null -> SymbolKind.HAS_RELATION to ctx.has_rule()
            ctx.knows_rule() != null -> SymbolKind.KNOWS_RELATION to ctx.knows_rule()
            else -> return null
        }
        val key = tree.quoted(tree.terminal(body, KmetaParser.STRING))
        val target = targetType(body.getRuleContext(KmetaParser.Rule_valueContext::class.java, 0))
        val node = outlineNode(kind, key?.value.orEmpty(), target, tree.rangeOf(body), tree.selectionOf(key, body))
        collector.declare(kind, key, node.range, container = typeName, typeName = target, span = SemanticKind.PROPERTY_NAME)
        return node
    }

    /** Records the type reference of [value] and returns the type as written: `T` or `list(T)`; null if missing. */
    private fun targetType(value: KmetaParser.Rule_valueContext?): String? {
        val type = tree.quoted(tree.terminal(value, KmetaParser.STRING)) ?: return null
        collector.typeReference(type)
        return if (tree.terminal(value, KmetaParser.LIST) != null) "list(${type.value})" else type.value
    }
}
