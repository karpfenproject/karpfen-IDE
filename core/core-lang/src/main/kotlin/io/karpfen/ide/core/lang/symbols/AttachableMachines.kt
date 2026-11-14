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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.SymbolKind
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.completion.nameItem
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.text.TextRange

/**
 * An attachable state machine of a modeling context, `STATEMACHINE "name" ATTACHABLE TO "A", "B"`.
 *
 * @property symbol the declaration of the machine with the outcome it was found in. Its name is the name of the
 *   machine.
 * @property types the types the machine is attachable to, in the order of its header.
 */
internal class AttachableMachine(val symbol: DeclaredSymbol, val types: List<String>) {

    /** The name of the machine. */
    val name: String get() = symbol.declaration.name

    /** Where the name of the machine is declared. */
    val location: Location get() = symbol.location

    /** The machine in words, for hover and completion, as in `state machine "blinking" attachable to Bulb, Lamp`. */
    val hoverText: String
        get() = "state machine \"$name\"" + if (types.isEmpty()) "" else " attachable to ${types.joinToString(", ")}"
}

/**
 * The attachable state machines that a request can name: those of the modeling context of the request's document,
 * which `behaves after`, `ATTACH` and `EXCHANGE` name across the files of the context. The document itself counts with
 * its own declarations by the staleness rule of `LanguageDocument`, every other `.kstates` file of the context with
 * the outcome of the analysis. A document outside every context knows only its own machine.
 *
 * The answers come from the IDE's own syntax index, so they are known while a file has errors that keep dsl-tools from
 * reading it. Each request makes its own instance, and nothing is cached.
 */
internal class AttachableMachines(private val request: FeatureRequest) {

    /** Every attachable state machine with a name, those of the document first, then the others in context order. */
    fun all(): List<AttachableMachine> {
        val document = request.document
        val own = if (document.language == KarpfenLanguage.KSTATES) {
            DocumentDeclarations(document).all(SymbolKind.STATEMACHINE).filter { it.declaration.isAttachableStateMachine }
        } else {
            emptyList()
        }
        val others = request.contextAnalysis?.files?.values.orEmpty()
            .filter { it.path != document.path && it.parse.language == KarpfenLanguage.KSTATES }
            .flatMap { file ->
                val source = LocatedOutcome.of(file)
                source.outcome.index.declarationsOf(SymbolKind.STATEMACHINE)
                    .filter { it.isAttachableStateMachine && it.name.isNotEmpty() }
                    .map { DeclaredSymbol(it, source) }
            }
        return (own + others).map { AttachableMachine(it, typesOf(it)) }
    }

    /** The attachable state machines named [name]. More than one is a finding of the verifier of dsl-tools. */
    fun named(name: String): List<AttachableMachine> = all().filter { it.name == name }

    /**
     * The completion items of the machines, each name once, replacing [range]. The machines attachable to
     * [suitedType] come first, if it is known. The detail lists the types of each machine.
     */
    fun items(range: TextRange, suitedType: String?, quoted: Boolean = false): List<CompletionItem> =
        all().distinctBy { it.name }.map { machine ->
            nameItem(
                machine.name,
                CompletionKind.STATE_MACHINE,
                range,
                detail = machine.types.joinToString(", ").ifEmpty { null },
                documentation = machine.hoverText,
                rank = if (suitedType != null && suitedType in machine.types) 0 else 1,
                quoted = quoted,
            )
        }

    /** The types after `ATTACHABLE TO` of the machine of [symbol], read from the detail of its outline node. */
    private fun typesOf(symbol: DeclaredSymbol): List<String> =
        symbol.source.outcome.outline
            .firstOrNull { it.kind == SymbolKind.STATEMACHINE && it.selectionRange == symbol.declaration.nameRange }
            ?.detail?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
}
