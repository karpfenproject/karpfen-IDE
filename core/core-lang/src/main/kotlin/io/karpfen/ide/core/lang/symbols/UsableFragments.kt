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
package io.karpfen.ide.core.lang.symbols

import io.karpfen.ide.core.document.parse.ReferenceKind
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.completion.nameItem
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.text.TextRange
import io.karpfen.ide.core.workspace.analysis.ProjectFragments

/**
 * The fragments that the name after `USE` of a container state can name, `STATEMACHINE "name" FRAGMENT`. Container
 * states find a fragment by its name in the flat name space of the project, so the fragments come from every analyzed
 * file of the project through [ProjectFragments], those of the modeling context of the request's document first. A
 * fragment never uses itself, so the fragment that the document itself declares is left out of completion.
 *
 * The answers come from the IDE's own syntax index, so they are known while a file has errors that keep dsl-tools from
 * reading it. Each request makes its own instance, and nothing is cached.
 */
internal class UsableFragments(private val request: FeatureRequest) {

    private val fragments = ProjectFragments(request.project)

    /** The name of the fragment the document declares, or null if it is no fragment. */
    private val own: String? = DocumentDeclarations(request.document).all(SymbolKind.FRAGMENT).firstOrNull()?.declaration?.name

    /** The completion items of the fragments other than the document's own, each name once, replacing [range]. */
    fun items(range: TextRange): List<CompletionItem> =
        fragments.names().filter { it != own }.map { name ->
            val file = fragments.named(name, request.document.path)?.path?.fileName?.toString()
            nameItem(name, CompletionKind.STATE_MACHINE, range, detail = file, documentation = hoverText(name))
        }

    /** Where the fragment [name] is declared, or null if no file of the project declares it. */
    fun declaration(name: String): Location? {
        if (name == own) return DocumentDeclarations(request.document).named(SymbolKind.FRAGMENT, name).firstOrNull()?.location
        val fragment = fragments.named(name, request.document.path) ?: return null
        return LocatedOutcome.of(fragment.file).location(fragment.declaration.nameRange)
    }

    /**
     * Where the fragment [name] is used after `USE`: in the latest parse of the document, then in the other analyzed
     * files of the project.
     */
    fun uses(name: String): List<Location> {
        val document = request.document
        val own = DocumentDeclarations(document).latest()?.let { source ->
            source.outcome.index.references(ReferenceKind.FRAGMENT, name).map { source.location(it.range) }
        }.orEmpty()
        val others = request.project.files.values.filter { it.path != document.path }.flatMap { file ->
            val source = LocatedOutcome.of(file)
            file.parse.index.references(ReferenceKind.FRAGMENT, name).map { source.location(it.range) }
        }
        return own + others
    }

    /** The fragment [name] in words, for hover and completion, as in `fragment "bulb test" in bulb test.frag.kstates`. */
    fun hoverText(name: String): String? {
        val file = fragments.named(name, request.document.path)?.path?.fileName?.toString()
            ?: return if (name == own) "fragment \"$name\"" else null
        return "fragment \"$name\" in $file"
    }
}
