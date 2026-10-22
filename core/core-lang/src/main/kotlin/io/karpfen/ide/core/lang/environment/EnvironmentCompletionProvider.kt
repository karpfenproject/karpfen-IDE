package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.sortKey
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * Completion in environment descriptors, by the [DescriptorSlot] at the caret and the [schema]:
 * - at a key: the keys the object does not have yet, required ones first, each with its documentation; outside quotes
 *   a key arrives with a value template of its shape ([ValueTemplates]), after the last member with the `,` it needs;
 * - at a file path: the workspace files that fit the key's role ([DescriptorProject.candidates]), relative to the
 *   descriptor's directory, the usual files of the role first (`EVENTS.kmeta` for the event metamodel, the other
 *   metamodels for the domain metamodel), and within those the files next to the descriptor first;
 * - at `attachedTo`: the keys of the objects of the descriptor's model, the objects whose type is the `ATTACHED TO` type
 *   of the entry's state machine first;
 * - at an event domain: the domains the descriptor's state machines use;
 * - at a whole number or a string with typical values: those values ([ValueShape.WholeNumber], [ValueShape.Text]);
 * - at the value of an object or list key: its template.
 *
 * Every proposal is JSON as it stands outside a string, escaped as JSON, and the slot's [SlotEdit] fits it to the
 * caret's place: it drops the quotes a string at the caret keeps, closes a string that has no closing quote, and adds the
 * `,` a new member or item lacks in front of or behind it, so accepting a proposal keeps the descriptor valid JSON
 * around it. Unfiltered, like every provider (the editor filters by the text before the caret).
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
            is DescriptorSlot.Value -> values(DescriptorProject(request), slot)
            DescriptorSlot.None -> emptyList()
        }
    }

    /** The keys the slot's object does not have yet, required ones first. */
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
            is ValueShape.Record, is ValueShape.ListOf -> if (slot.inString || slot.node != null) {
                emptyList()
            } else {
                listOf(Snippet(ValueTemplates.label(shape), ValueTemplates.of(shape), CompletionKind.SNIPPET, shape.description).item(slot.replaceRange))
            }
        }

    /**
     * The keys of the model's objects for an `attachedTo`, the objects that suit the state machine of the same entry
     * first.
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

    /** Number or literal proposals, only outside a string, in the given order. */
    private fun literals(slot: DescriptorSlot.Value, values: List<String>): List<CompletionItem> =
        if (slot.edit.isOutsideString) {
            values.mapIndexed { index, value ->
                val detail = slot.key.shape.description
                item(slot.edit, Template(value), value, CompletionKind.VALUE, detail, slot.key.documentation, rank = index)
            }
        } else {
            emptyList()
        }

    /**
     * The rank of a [file] the descriptor can name: the usual files of the role before the others, and within each
     * group the files next to the descriptor first, then those below it, then those elsewhere.
     */
    private fun rankOf(file: FileCandidate): Int {
        val nearness = when {
            '/' !in file.path -> 0
            !file.path.startsWith("..") -> 1
            else -> 2
        }
        return if (file.isUsual) nearness else NEARNESS_RANKS + nearness
    }

    /** The detail of a key in the list: what its value is, and whether it is required. */
    private fun detailOf(key: DescriptorKey): String = if (key.isRequired) "${key.shape.description}, required" else key.shape.description

    private companion object {
        /** The number of nearness ranks of a file, so the files that are not usual rank after all usual ones. */
        const val NEARNESS_RANKS = 3
    }
}
