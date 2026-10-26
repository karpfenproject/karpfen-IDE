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
package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.modelpath.PathType
import io.karpfen.ide.core.lang.symbols.DocumentDeclarations
import io.karpfen.ide.core.lang.symbols.MemberInfo

/**
 * The texts KStates completion and hover show for its symbols: macro signatures, state hierarchy paths and
 * type texts. Pure functions over declarations.
 */
internal object KstatesTexts {

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

    /** The type [name] as written, wrapped in `list(…)` if [isList]; `?` for an unknown name. */
    private fun written(name: String, isList: Boolean): String {
        val shown = name.ifEmpty { "?" }
        return if (isList) "list($shown)" else shown
    }

    /** Bound of the parent chain of a state hierarchy path, which also stops a cycle in broken text. */
    private const val MAX_DEPTH = 64
}
