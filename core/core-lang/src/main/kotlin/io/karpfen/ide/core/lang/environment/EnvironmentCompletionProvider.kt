package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.DescriptorKey
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonStrings
import io.karpfen.ide.core.lang.CompletionItem
import io.karpfen.ide.core.lang.CompletionKind
import io.karpfen.ide.core.lang.completion.Snippet
import io.karpfen.ide.core.lang.completion.sortKey
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * Completion in environment descriptors, by the [DescriptorSlot] at the caret and the [schema]:
 * - at a key: the keys the object does not have yet, required ones first, each with its documentation; outside quotes
 *   a key arrives with a value template of its shape ([ValueTemplates]), after the last member with the `,` it needs;
 * - at a file path: the workspace files of the key's role ([DescriptorProject.candidates]: metamodels without
 *   `EVENTS.kmeta`, the event metamodel, models, state machines, bridge configurations), relative to the descriptor's
 *   directory, files next to it first;
 * - at `attachedTo`: the keys of the objects of the descriptor's model, the objects whose type is the `ATTACHED TO` type
 *   of the entry's state machine first;
 * - at an event domain: the domains the descriptor's state machines use;
 * - at a whole number, `true`/`false` or a string with typical values: those values ([ValueShape.WholeNumber],
 *   [ValueShape.Flag], [ValueShape.Text]);
 * - at the value of an object or list key: its template.
 *
 * Strings are inserted with quotes outside a string literal and escaped as JSON in any case. Unfiltered, like every
 * provider (the editor filters by the text before the caret).
 */
internal class EnvironmentCompletionProvider(
    private val trees: DescriptorTrees,
    private val caret: DescriptorCaret,
    private val schema: ValueShape.Record,
) : CompletionProvider {

    override fun complete(request: FeatureRequest, offset: Int): List<CompletionItem> {
        val text = request.document.text
        return when (val slot = caret.slotAt(trees.of(text), offset, text.length)) {
            is DescriptorSlot.Key -> keys(slot)
            is DescriptorSlot.Value -> values(DescriptorProject(request), slot)
            DescriptorSlot.None -> emptyList()
        }
    }

    /** The keys the slot's object does not have yet, required ones first. */
    private fun keys(slot: DescriptorSlot.Key): List<CompletionItem> =
        slot.shape?.keys.orEmpty().filter { it.name !in slot.presentKeys }.map { key ->
            val template = if (slot.inString) JsonStrings.encode(key.name) else slot.separator + ValueTemplates.member(key)
            Snippet(key.name, template, CompletionKind.PROPERTY, detailOf(key))
                .item(slot.replaceRange, rank = if (key.isRequired) 0 else 1)
                .copy(documentation = key.documentation)
        }

    /** The proposals for the value of the slot, by its shape. */
    private fun values(project: DescriptorProject, slot: DescriptorSlot.Value): List<CompletionItem> =
        when (val shape = slot.shape) {
            is ValueShape.FilePath -> project.candidates(shape.role).map { path ->
                string(slot, path, CompletionKind.FILE, shape.role.description, rank = nearness(path))
            }
            ValueShape.ObjectKey -> objectKeys(project, slot)
            ValueShape.EventDomain -> project.eventDomainsOf(project.filesNamed(DescriptorFileRole.STATE_MACHINE, slot.root, schema))
                .map { string(slot, it, CompletionKind.EVENT, "event domain") }
            is ValueShape.Text -> shape.suggestions.map { string(slot, it, CompletionKind.VALUE, detail = null) }
            is ValueShape.WholeNumber -> literals(slot, shape.suggestions.map(Long::toString))
            ValueShape.Flag -> literals(slot, listOf("true", "false"))
            is ValueShape.Record, is ValueShape.ListOf -> if (slot.inString || slot.node != null) {
                emptyList()
            } else {
                listOf(Snippet(ValueTemplates.label(shape), ValueTemplates.of(shape), CompletionKind.SNIPPET, shape.description).item(slot.replaceRange))
            }
        }

    /**
     * The keys of the model's objects for an `attachedTo`; the objects whose type is the `ATTACHED TO` type of the state
     * machine of the same entry rank first.
     */
    private fun objectKeys(project: DescriptorProject, slot: DescriptorSlot.Value): List<CompletionItem> {
        val model = project.fileNamed(DescriptorFileRole.MODEL, slot.root, schema) ?: return emptyList()
        val machine = project.fileNamed(DescriptorFileRole.STATE_MACHINE, slot.owner, slot.ownerShape)
        val type = machine?.let(project::attachedTypeOf)
        return project.objectsOf(model).distinctBy { it.name }.map { declaration ->
            val matches = type != null && declaration.typeName == type
            string(slot, declaration.name, CompletionKind.OBJECT, declaration.typeName, rank = if (matches) 0 else 1)
        }
    }

    /** A string proposal [value]: quoted outside a string literal, JSON-escaped always. */
    private fun string(slot: DescriptorSlot.Value, value: String, kind: CompletionKind, detail: String?, rank: Int = 1): CompletionItem =
        CompletionItem(
            label = value,
            kind = kind,
            insertText = if (slot.inString) JsonStrings.encode(value) else ValueTemplates.quoted(value),
            replaceRange = slot.replaceRange,
            caretOffsetInInsert = null,
            detail = detail,
            documentation = slot.key.documentation,
            sortKey = sortKey(rank, value),
        )

    /** Number or literal proposals, only outside a string literal, in the given order. */
    private fun literals(slot: DescriptorSlot.Value, values: List<String>): List<CompletionItem> =
        if (slot.inString) {
            emptyList()
        } else {
            values.mapIndexed { index, value ->
                Snippet.keyword(value, slot.key.shape.description).item(slot.replaceRange, rank = index)
                    .copy(kind = CompletionKind.VALUE, documentation = slot.key.documentation)
            }
        }

    /** The rank of a file [path] as the descriptor names it: next to it first, then below it, then elsewhere. */
    private fun nearness(path: String): Int = when {
        '/' !in path -> 0
        !path.startsWith("..") -> 1
        else -> 2
    }

    /** The detail of a key in the list: what its value is, and whether it is required. */
    private fun detailOf(key: DescriptorKey): String = if (key.isRequired) "${key.shape.description}, required" else key.shape.description
}
