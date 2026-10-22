package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolDeclaration
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.document.parse.SyntaxIndex
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.modelpath.pathSegmentsOf
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations

/**
 * The file-local references of a KStates document (states and macros, WITH bindings and macro parameters):
 * declarations by name (staleness rule, [DocumentDeclarations]) and the references of the document's latest parse
 * outcome. One instance per request.
 */
internal class KstatesLocalReferences(private val document: LanguageDocument) {

    private val declarations = DocumentDeclarations(document)

    /** The declarations of [kind] named [name] and the references of [reference] to it. */
    fun of(kind: SymbolKind, reference: ReferenceKind, name: String): List<Location> {
        val references = declarations.latest()?.let { source ->
            source.outcome.index.references(reference, name).map { source.location(it.range) }
        }.orEmpty()
        return declarations.named(kind, name).map { it.location } + references
    }

    /**
     * The uses of the WITH binding or macro parameter [name] in scope at [offset]: the first segments of the model paths
     * starting with it in the innermost WITH block binding it (after its `AS "x"`) or in the enclosing macro declaring
     * it. Offset-based, so only with a parse of the current text (staleness rule); empty otherwise.
     */
    fun ofBinding(name: String, offset: Int): List<Location> {
        val source = declarations.latest()?.takeIf { it.outcome.version == document.text.version } ?: return emptyList()
        val index = source.outcome.index
        val scope = index.enclosingDeclarations(offset).lastOrNull { declares(index, it, name) } ?: return emptyList()
        val start = if (scope.kind == SymbolKind.BINDING) scope.nameRange.end else scope.range.start
        return index.referencesOf(ReferenceKind.MODEL_PATH)
            .filter { it.range.start >= start && it.range in scope.range }
            .mapNotNull { reference ->
                pathSegmentsOf(reference.name, reference.range, document.text.text).firstOrNull()?.takeIf { it.name == name }?.range
            }
            .map(source::location)
    }

    /** True if [declaration] is the binding [name] or a macro with the parameter [name]. */
    private fun declares(index: SyntaxIndex, declaration: SymbolDeclaration, name: String): Boolean = when (declaration.kind) {
        SymbolKind.BINDING -> declaration.name == name
        SymbolKind.MACRO -> index.declarationsOf(SymbolKind.MACRO_PARAMETER).any { it.container == declaration.name && it.name == name }
        else -> false
    }
}
