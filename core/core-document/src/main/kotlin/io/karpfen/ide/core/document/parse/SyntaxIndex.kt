package io.karpfen.ide.core.document.parse

/**
 * Declarations and references of one document, with lookups by offset, name and kind. Immutable and
 * thread-safe; equality is by content.
 *
 * Offset lookups use inclusive bounds (`start <= offset <= end`), so a caret directly after a name still finds it.
 *
 * @param declarations the declarations, in any order; exposed sorted by [SymbolDeclaration.nameRange] start.
 * @param references the references, in any order; exposed sorted by [SymbolReference.range] start.
 */
public class SyntaxIndex(declarations: List<SymbolDeclaration>, references: List<SymbolReference>) {

    /** All declarations in document order. */
    public val declarations: List<SymbolDeclaration> = declarations.sortedBy { it.nameRange.start }

    /** All references in document order. */
    public val references: List<SymbolReference> = references.sortedBy { it.range.start }

    private val declarationsByKey: Map<Pair<SymbolKind, String>, List<SymbolDeclaration>> by lazy {
        this.declarations.groupBy { it.kind to it.name }
    }

    private val referencesByKey: Map<Pair<ReferenceKind, String>, List<SymbolReference>> by lazy {
        this.references.groupBy { it.kind to it.name }
    }

    /** The declaration whose name range contains [offset], or null. */
    public fun declarationAt(offset: Int): SymbolDeclaration? =
        declarations.firstOrNull { it.nameRange.containsInclusive(offset) }

    /** The innermost (shortest) reference whose range contains [offset], or null. */
    public fun referenceAt(offset: Int): SymbolReference? =
        references.filter { it.range.containsInclusive(offset) }.minByOrNull { it.range.length }

    /** Declarations of [kind] named [name], in document order. */
    public fun declarations(kind: SymbolKind, name: String): List<SymbolDeclaration> =
        declarationsByKey[kind to name].orEmpty()

    /** All declarations of [kind], in document order. */
    public fun declarationsOf(kind: SymbolKind): List<SymbolDeclaration> = declarations.filter { it.kind == kind }

    /** References of [kind] to [name], in document order. */
    public fun references(kind: ReferenceKind, name: String): List<SymbolReference> =
        referencesByKey[kind to name].orEmpty()

    /** All references of [kind], in document order. */
    public fun referencesOf(kind: ReferenceKind): List<SymbolReference> = references.filter { it.kind == kind }

    /**
     * Declarations whose whole [SymbolDeclaration.range] contains [offset] (inclusive), outermost first; e.g. the
     * chain of states around a caret, or the object whose body the caret is in.
     */
    public fun enclosingDeclarations(offset: Int): List<SymbolDeclaration> =
        declarations
            .filter { it.range.containsInclusive(offset) }
            .sortedWith(compareBy<SymbolDeclaration> { it.range.start }.thenByDescending { it.range.end })

    override fun equals(other: Any?): Boolean =
        other is SyntaxIndex && declarations == other.declarations && references == other.references

    override fun hashCode(): Int = 31 * declarations.hashCode() + references.hashCode()

    override fun toString(): String = "SyntaxIndex(declarations=${declarations.size}, references=${references.size})"

    public companion object {
        /** An index without declarations or references. */
        public val EMPTY: SyntaxIndex = SyntaxIndex(emptyList(), emptyList())
    }
}
