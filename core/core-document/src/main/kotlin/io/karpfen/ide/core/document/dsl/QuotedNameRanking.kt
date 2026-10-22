package io.karpfen.ide.core.document.dsl

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.text.TextRange

/**
 * Ranks the symbols of [index] whose names [message] quotes, as [DslErrorLocator] documents: the symbols of the name
 * quoted first are the candidates, and the other quoted names scope them. Ranges that end after [textLength] (an index
 * of another text) take no part. One instance per message; not thread-safe.
 */
internal class QuotedNameRanking(
    private val message: String,
    private val textLength: Int,
    private val index: SyntaxIndex,
) {

    /** A symbol that may be meant: a declaration's name range or a reference's range. */
    private class Candidate(val name: String, val range: TextRange, val qualifier: String?, val declaration: SymbolDeclaration?)

    /** The ranking key of a candidate; smaller is better, field by field. */
    private data class Rank(val distance: Int, val unqualified: Int, val wellFormed: Int, val start: Int, val end: Int) :
        Comparable<Rank> {
        override fun compareTo(other: Rank): Int =
            compareValuesBy(this, other, Rank::distance, Rank::unqualified, Rank::wellFormed, Rank::start, Rank::end)
    }

    /** The memoized results of [positionOf]. */
    private val positions = HashMap<String, Int>()

    /** The range of the best candidate, or null if the message quotes no symbol name. */
    fun best(): TextRange? {
        val named = candidates()
        val subject = named.minByOrNull { positionOf(it.name) }?.name ?: return null
        val scopes = index.declarations.filter { it.name != subject && isUsable(it.range) && positionOf(it.name) >= 0 }
        return named.filter { it.name == subject }.minByOrNull { rank(it, scopes) }?.range
    }

    /** Every usable declaration and reference whose name the message quotes. */
    private fun candidates(): List<Candidate> {
        val declarations = index.declarations.map { Candidate(it.name, it.nameRange, qualifier = null, declaration = it) }
        val references = index.references.map { Candidate(it.name, it.range, it.qualifier, declaration = null) }
        return (declarations + references).filter { isUsable(it.range) && positionOf(it.name) >= 0 }
    }

    /** The [Rank] of [candidate] given the declarations the message's other quoted names name. */
    private fun rank(candidate: Candidate, scopes: List<SymbolDeclaration>): Rank =
        Rank(
            distance = distance(candidate, scopes),
            unqualified = if (candidate.qualifier != null && positionOf(candidate.qualifier) >= 0) 0 else 1,
            wellFormed = if (isMalformedProperty(candidate.declaration)) 0 else 1,
            start = candidate.range.start,
            end = candidate.range.end,
        )

    /**
     * The fewest declarations between [candidate] and a scope declaration around it (0: the scope is the innermost
     * declaration around it); [Int.MAX_VALUE] if no scope contains it.
     */
    private fun distance(candidate: Candidate, scopes: List<SymbolDeclaration>): Int =
        scopes.filter { candidate.range in it.range }.minOfOrNull { scope ->
            index.declarations.count {
                it !== scope && it !== candidate.declaration && candidate.range in it.range && it.range in scope.range
            }
        } ?: Int.MAX_VALUE

    /** True for a KMeta `prop` whose value type is not a primitive (a TYPE reference inside it): dsl-tools rejects it. */
    private fun isMalformedProperty(declaration: SymbolDeclaration?): Boolean =
        declaration?.kind == SymbolKind.SIMPLE_PROPERTY &&
            index.referencesOf(ReferenceKind.TYPE).any { it.range in declaration.range }

    /** True if [range] lies inside the text the message is about. */
    private fun isUsable(range: TextRange): Boolean = range.end <= textLength

    /** Where [name] first appears quoted (`'name'` or `"name"`) in the message; -1 if it does not (or is empty). */
    private fun positionOf(name: String): Int =
        positions.getOrPut(name) {
            if (name.isEmpty()) {
                -1
            } else {
                listOf(message.indexOf("'$name'"), message.indexOf("\"$name\"")).filter { it >= 0 }.minOrNull() ?: -1
            }
        }
}
