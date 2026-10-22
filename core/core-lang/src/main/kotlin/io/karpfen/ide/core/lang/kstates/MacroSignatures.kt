package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.symbols.TypeExpression

/**
 * The RETURNS and TAKES types of the macros of a document, from its syntax indexes (priority order, name-based).
 */
internal class MacroSignatures(private val indexes: List<SyntaxIndex>) {

    /** The RETURNS type of [macro] (`reference(T)` is `T`), or null if unknown. */
    fun returns(macro: String): PathType? =
        indexes.firstNotNullOfOrNull { it.declarations(SymbolKind.MACRO, macro).firstOrNull() }?.typeName?.let(::pathTypeOf)

    /** The TAKES parameters of [macro] with their types, in declaration order. */
    fun parameters(macro: String): List<Pair<String, PathType>> =
        indexes.firstNotNullOfOrNull { index ->
            index.declarationsOf(SymbolKind.MACRO_PARAMETER)
                .filter { it.container == macro && it.name.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
        }.orEmpty().map { it.name to pathTypeOf(it.typeName) }

    /** The path type of a type as written in TAKES or RETURNS. */
    private fun pathTypeOf(written: String?): PathType =
        TypeExpression.parse(written).let { PathType(it.name, it.isList, it.isPrimitive) }
}
