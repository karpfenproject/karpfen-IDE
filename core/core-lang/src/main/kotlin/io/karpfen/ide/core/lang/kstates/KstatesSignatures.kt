package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.MemberInfo

/**
 * The texts KStates completion and hover show for its symbols (spec §5): macro signatures, state hierarchy paths and
 * type texts. Pure functions over declarations.
 */
internal object KstatesSignatures {

    /** `macro m(p1: TwoDObject, p2: list(Obstacle)): number` from the macro's TAKES and RETURNS declarations. */
    fun macro(declarations: DocumentDeclarations, name: String): String {
        val parameters = declarations.all(SymbolKind.MACRO_PARAMETER).map { it.declaration }.filter { it.container == name }
            .joinToString(", ") { "${it.name}: ${it.typeName.orEmpty()}" }
        val returns = declarations.named(SymbolKind.MACRO, name).firstOrNull()?.declaration?.typeName
        return "macro $name($parameters)" + (returns?.let { ": $it" } ?: "")
    }

    /** `state observe > react to obstacle`: the state's parents (by `container`) and the state. */
    fun state(declarations: DocumentDeclarations, name: String): String {
        val chain = ArrayList<String>()
        var current: String? = name
        while (current != null && current !in chain && chain.size < MAX_DEPTH) {
            chain += current
            current = declarations.named(SymbolKind.STATE, current).firstOrNull()?.declaration?.container
        }
        return "state " + chain.asReversed().joinToString(" > ")
    }

    /** `Vector`, `list(Obstacle)`, `number`; `?` for an unknown type. */
    fun typeText(type: PathType): String = written(type.name, type.isList)

    /** The member's type as written: `Vector`, `list(Obstacle)`. */
    fun typeText(member: MemberInfo): String = written(member.typeName, member.isList)

    private fun written(name: String, isList: Boolean): String {
        val shown = name.ifEmpty { "?" }
        return if (isList) "list($shown)" else shown
    }

    private const val MAX_DEPTH = 64
}
