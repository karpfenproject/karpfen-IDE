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
package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonLiteral
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.sortKey
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * Completion in environment descriptors, by the [DescriptorSlot] at the caret and the [schema]. It proposes the
 * following.
 * - In an empty descriptor, the descriptor object with its required keys.
 * - At a key, the keys the object does not have yet, required ones first, each with its documentation. A new key, or a
 *   key string without `:` or value, arrives with a value template of its shape from [ValueTemplates]. A key with a
 *   value but no `:` gets its `:`. A key that has a `:`, also one written without quotes, is renamed. The
 *   [DescriptorSlot.Key.part] tells these cases apart.
 * - At a file path, the workspace files that fit the role of the key, as [DescriptorProject.candidates] lists them,
 *   relative to the directory of the descriptor. The usual files of the role come first. These are `EVENTS.kmeta` for
 *   the event metamodel and the other metamodels for the domain metamodel. Within each group the files next to the
 *   descriptor come first.
 * - At `attachedTo`, the keys of the objects of the model of the descriptor. They are ranked for the state machine of
 *   the entry by the same rule the template of new descriptors uses, `ContextSymbols.attachmentCandidates`.
 * - At an event domain, the domains the state machines of the descriptor use.
 * - At a whole number or a string with typical values, those values from [ValueShape.WholeNumber] or
 *   [ValueShape.Text].
 * - At the value of an object or list key, its template.
 * - On a value the user writes out by hand, nothing. Such a value is a number, a literal or a bare word that begins a
 *   literal, as [isWrittenOut] decides.
 *
 * Every proposal is JSON as it stands outside a string, escaped as JSON. The [SlotEdit] of the slot fits it to the
 * place of the caret. It drops the quotes that a string at the caret keeps. It closes a string that has no closing
 * quote. It adds the `,` that a new member or item lacks in front of or behind it. So accepting a proposal keeps the
 * descriptor valid JSON around it. Like every provider, this one does not filter. The editor filters by the text before
 * the caret.
 */
internal class EnvironmentCompletionProvider(
    private val trees: DescriptorTrees,
    private val caret: DescriptorCaret,
    private val schema: ValueShape.Record,
) : CompletionProvider {

    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> {
        val text = request.document.text
        return when (val slot = caret.slotAt(trees.of(text), text.text, offset)) {
            is DescriptorSlot.Root -> listOf(template(slot.edit, slot.shape, ROOT_DETAIL))
            is DescriptorSlot.Key -> keys(slot)
            is DescriptorSlot.Value -> if (isWrittenOut(slot, text.text)) emptyList() else values(DescriptorProject(request), slot)
            DescriptorSlot.None -> emptyList()
        }
    }

    /** The keys the object of the slot does not have yet, required ones first. */
    private fun keys(slot: DescriptorSlot.Key): List<CompletionItem> =
        slot.shape?.keys.orEmpty().filter { it.name !in slot.presentKeys }.map { key ->
            val proposal = when (slot.part) {
                KeyPart.NAME -> ValueTemplates.name(key)
                KeyPart.NAME_AND_COLON -> ValueTemplates.nameAndColon(key)
                KeyPart.MEMBER -> ValueTemplates.member(key)
            }
            val rank = if (key.isRequired) 0 else 1
            item(slot.edit, proposal, key.name, CompletionKind.PROPERTY, detailOf(key), key.documentation, rank)
        }

    /**
     * True if the caret of [slot] is on a value the user writes out by hand. Such a value is a number, a literal, or a
     * bare word of [text] that begins a literal, such as `nu` on the way to `null`. Completion offers nothing there.
     * The editor opens the list by itself once a second letter or digit is typed. It filters the list by any proposal
     * that holds the typed characters in order, and it accepts the highlighted proposal on Enter. So a proposal would
     * replace the fully typed value on the Enter that ends the line. For example, `10` would become the typical `100`,
     * and `null` would become a file path that holds n, u, l and l. Typical values are still proposed where no value is
     * typed yet. File paths and other strings are still proposed inside a string or over a word that begins no literal.
     */
    private fun isWrittenOut(slot: DescriptorSlot.Value, text: String): Boolean {
        val range = slot.edit.range
        return when (slot.node) {
            is JsonNode.NumberNode, is JsonNode.LiteralNode -> true
            null -> slot.edit.isOutsideString && !range.isEmpty && beginsLiteral(text.substring(range.start, range.end))
            else -> false
        }
    }

    /** True if [word] is the start of one of the JSON literals `true`, `false` and `null`. */
    private fun beginsLiteral(word: String): Boolean = JsonLiteral.entries.any { it.text.startsWith(word) }

    /** The proposals for the value of the slot, by its shape. */
    private fun values(project: DescriptorProject, slot: DescriptorSlot.Value): List<CompletionItem> =
        when (val shape = slot.shape) {
            is ValueShape.FilePath -> project.candidates(shape.role).map { file ->
                string(slot, file.path, CompletionKind.FILE, shape.role.description, rank = rankOf(file))
            }
            ValueShape.ObjectKey -> objectKeys(project, slot)
            ValueShape.EventDomain -> {
                val machines = project.filesNamed(DescriptorFileRole.STATE_MACHINE, slot.root, schema)
                project.symbols.eventDomainsOf(machines).map { string(slot, it, CompletionKind.EVENT, "event domain") }
            }
            is ValueShape.Text -> shape.suggestions.map { string(slot, it, CompletionKind.VALUE, detail = null) }
            is ValueShape.WholeNumber -> literals(slot, shape.suggestions.map(Long::toString))
            is ValueShape.Record, is ValueShape.ListOf ->
                if (slot.edit.isOutsideString && slot.node == null) listOf(template(slot.edit, shape, shape.description)) else emptyList()
        }

    /**
     * The keys of the objects of the model for an `attachedTo`. The objects that suit the state machine of the same
     * entry come first.
     */
    private fun objectKeys(project: DescriptorProject, slot: DescriptorSlot.Value): List<CompletionItem> {
        val model = project.fileNamed(DescriptorFileRole.MODEL, slot.root, schema) ?: return emptyList()
        val machine = project.fileNamed(DescriptorFileRole.STATE_MACHINE, slot.owner, slot.ownerShape)
        return project.symbols.attachmentCandidates(model, machine).map { candidate ->
            val modelObject = candidate.modelObject
            string(slot, modelObject.key, CompletionKind.OBJECT, modelObject.typeName, rank = if (candidate.suitsMachine) 0 else 1)
        }
    }

    /** A string proposal [value]. */
    private fun string(slot: DescriptorSlot.Value, value: String, kind: CompletionKind, detail: String?, rank: Int = 1): CompletionItem =
        item(slot.edit, ValueTemplates.string(value), value, kind, detail, slot.key.documentation, rank)

    /** Number or literal proposals in the given order. They are made only outside a string. */
    private fun literals(slot: DescriptorSlot.Value, values: List<String>): List<CompletionItem> =
        if (slot.edit.isOutsideString) {
            values.mapIndexed { index, value ->
                val detail = slot.key.shape.description
                item(slot.edit, Template(value), value, CompletionKind.VALUE, detail, slot.key.documentation, rank = index)
            }
        } else {
            emptyList()
        }

    /** The value template of [shape], labelled `{ … }` or `[ … ]`. */
    private fun template(edit: SlotEdit, shape: ValueShape, detail: String): CompletionItem =
        item(edit, ValueTemplates.of(shape), ValueTemplates.label(shape), CompletionKind.SNIPPET, detail, documentation = null)

    /**
     * The item that writes [proposal] by [edit]. The caret goes to the place to fill in the proposal. The item names no
     * caret offset when that place is the end of the insert.
     */
    private fun item(
        edit: SlotEdit,
        proposal: Template,
        label: String,
        kind: CompletionKind,
        detail: String?,
        documentation: String?,
        rank: Int = 1,
    ): CompletionItem {
        val insert = edit.fit(proposal)
        return CompletionItem(
            label = label,
            kind = kind,
            insertText = insert.text,
            replaceRange = edit.range,
            caretOffsetInInsert = insert.caret.takeIf { it != insert.text.length },
            detail = detail,
            documentation = documentation,
            sortKey = sortKey(rank, label),
        )
    }

    /**
     * The rank of a [file] the descriptor can name. The usual files of the role come before the others. Within each
     * group the files next to the descriptor come first, then those below it, then those elsewhere.
     */
    private fun rankOf(file: FileCandidate): Int {
        val nearness = when {
            '/' !in file.path -> 0
            !file.path.startsWith("..") -> 1
            else -> 2
        }
        return if (file.isUsual) nearness else NEARNESS_RANKS + nearness
    }

    /** The detail of a key in the list. It says what its value is and whether it is required. */
    private fun detailOf(key: DescriptorKey): String = if (key.isRequired) "${key.shape.description}, required" else key.shape.description

    private companion object {
        /** The detail of the descriptor object proposed in an empty descriptor. */
        const val ROOT_DETAIL = "an environment descriptor"

        /** The number of nearness ranks of a file, so the files that are not usual rank after all usual ones. */
        const val NEARNESS_RANKS = 3
    }
}
