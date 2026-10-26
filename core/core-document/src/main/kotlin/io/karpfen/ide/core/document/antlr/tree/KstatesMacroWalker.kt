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

import io.karpfen.dsl.grammar.kstates.KstatesParser
import io.karpfen.ide.core.document.parse.OutlineNode
import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.parse.SymbolKind

/**
 * Walks the `MACROS { … }` block of a KStates tree:
 * - `MACRO "m" { … }` → MACRO declaration (MACRO_NAME span) whose type name and outline detail are the RETURNS
 *   expression as written without quotes (`number`, `Vector`, `list(Obstacle)`, `reference(Obstacle)`);
 * - `TAKES("p", T)` → MACRO_PARAMETER declaration (container: the macro; type name and detail: `T` or `list(T)`) with a
 *   BINDING_NAME span (a parameter is bound like a `WITH … AS` name inside the definition), as a child of the macro's
 *   outline node;
 * - the type strings of TAKES, RETURNS, `list(…)` and `reference(…)` → type references (PRIMITIVE_TYPE for `number`,
 *   `string`, `boolean`, else TYPE). The DEFINITION's EVAL body is read lexically (see [EmbeddedCode]).
 */
internal class KstatesMacroWalker(private val tree: ParsedTree<*>, private val collector: SyntaxCollector) {

    /** The outline nodes of the macros of [block], in document order. */
    fun walk(block: KstatesParser.Macros_blockContext?): List<OutlineNode> = block?.macro_definition().orEmpty().map(::macro)

    /** Records the macro of [ctx] and returns its outline node, whose children are its parameters. */
    private fun macro(ctx: KstatesParser.Macro_definitionContext): OutlineNode {
        val name = tree.quoted(tree.terminal(ctx, KstatesParser.STRING))
        val macroName = name?.value.orEmpty()
        val parameters = ctx.takes_directive().map { parameter(it, macroName) }
        val returns = returnType(ctx.returns_directive()?.return_expression())
        val node = outlineNode(SymbolKind.MACRO, macroName, returns, tree.rangeOf(ctx), tree.selectionOf(name, ctx), parameters)
        collector.declare(SymbolKind.MACRO, name, node.range, container = null, typeName = returns, span = SemanticKind.MACRO_NAME)
        return node
    }

    /** Records a `TAKES` parameter of [macro] and returns its outline node. */
    private fun parameter(ctx: KstatesParser.Takes_directiveContext, macro: String): OutlineNode {
        val name = tree.quoted(tree.terminal(ctx, KstatesParser.STRING))
        val type = typeExpression(ctx.type_expression())
        val node = outlineNode(SymbolKind.MACRO_PARAMETER, name?.value.orEmpty(), type, tree.rangeOf(ctx), tree.selectionOf(name, ctx))
        collector.declare(SymbolKind.MACRO_PARAMETER, name, node.range, container = macro, typeName = type, span = SemanticKind.BINDING_NAME)
        return node
    }

    /** Records the type reference of a RETURNS expression; returns it as written (null if its type is missing). */
    private fun returnType(ctx: KstatesParser.Return_expressionContext?): String? {
        val reference = ctx?.reference_expression() ?: return typeExpression(ctx?.type_expression())
        return typeCall("reference", tree.quoted(tree.terminal(reference, KstatesParser.STRING)))
    }

    /** Records the type reference of `"T"` or `f("T")`; returns it as written (null if its type is missing). */
    private fun typeExpression(ctx: KstatesParser.Type_expressionContext?): String? {
        val call = ctx?.typed_call_expression()
        if (call == null) {
            val type = tree.quoted(tree.terminal(ctx, KstatesParser.STRING)) ?: return null
            collector.typeReference(type)
            return type.value
        }
        val function = tree.terminal(call, KstatesParser.IDENT)?.takeIf { tree.rangeOf(it) != null }?.text.orEmpty()
        return typeCall(function, tree.quoted(tree.terminal(call, KstatesParser.STRING)))
    }

    /** Records the type reference of `function("T")`; returns it as written (null if its type is missing). */
    private fun typeCall(function: String, type: QuotedString?): String? {
        type ?: return null
        collector.typeReference(type)
        return "$function(${type.value})"
    }
}
