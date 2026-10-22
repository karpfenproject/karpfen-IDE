package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.context.CallContext
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.text.TextRange

/** What the caret is on or in, in a KMeta document; the one classification of completion, hover and navigation. */
internal sealed interface KmetaSlot {
    /** Between types: the `type` snippet. */
    data object TopLevel : KmetaSlot

    /** In the body of the type [owner], outside a rule: the rule snippets. */
    data class TypeBody(val owner: String?) : KmetaSlot

    /** The name of `type "N"`. */
    data class TypeName(val name: String, val range: TextRange) : KmetaSlot

    /** The key of `prop("k"`, `has("k"`, `knows("k"` in the body of [owner]. */
    data class MemberName(val owner: String?, val kind: MemberKind, val name: String, val range: TextRange) : KmetaSlot

    /**
     * A type argument: [primitive] for `prop(…, "…")` (also inside `list(`), else a type reference of `has`/`knows`.
     * [inString] is false when the caret is at the argument but not inside quotes yet ([name] is then empty);
     * [listAllowed] is false inside `list(` itself, where no further `list("…")` is offered.
     */
    data class TypeArgument(
        val primitive: Boolean,
        val name: String,
        val range: TextRange,
        val inString: Boolean,
        val listAllowed: Boolean,
    ) : KmetaSlot

    /** Anything else (descriptions, comments, broken positions). */
    data object None : KmetaSlot
}

/** Classifies the caret of a KMeta document from its [CaretContext]. Stateless. */
internal class KmetaSlots(private val caretContexts: CaretContextDetector) {

    /** The caret context at [offset] and its slot. */
    fun at(document: LanguageDocument, offset: Int): Pair<CaretContext, KmetaSlot> {
        val context = caretContexts.detect(document, offset)
        return context to classify(document, context)
    }

    /** The slot of [context]: names in strings by the call or keyword before them, else the block the caret is in. */
    private fun classify(document: LanguageDocument, context: CaretContext): KmetaSlot {
        if (context.inComment) return KmetaSlot.None
        val owner = context.blocks.asReversed().firstNotNullOfOrNull { typeNameOf(it.header) }
        val call = context.call
        val string = context.string
        if (string != null) {
            val range = if (string.closed) string.contentRange else context.replaceRange
            val name = document.text.substring(range)
            val afterTypeKeyword = context.statement.lastOrNull()?.let { it.kind == TokenKind.KEYWORD && it.text == TYPE } == true
            val member = call?.takeIf { it.argumentIndex == 0 }?.let { MemberKind.ofKeyword(it.name) }
            return when {
                call == null && afterTypeKeyword -> KmetaSlot.TypeName(name, range)
                call == null -> KmetaSlot.None
                member != null -> KmetaSlot.MemberName(owner, member, name, range)
                else -> typeArgument(call, name, range, inString = true) ?: KmetaSlot.None
            }
        }
        if (call != null) return typeArgument(call, "", context.replaceRange, inString = false) ?: KmetaSlot.None
        return when {
            context.blocks.isEmpty() -> if (context.statement.isEmpty()) KmetaSlot.TopLevel else KmetaSlot.None
            typeNameOf(context.blocks.last().header) != null -> KmetaSlot.TypeBody(owner)
            else -> KmetaSlot.None
        }
    }

    /** The type argument at [call], or null if the caret is in no type argument of a rule or of `list(`. */
    private fun typeArgument(call: CallContext, name: String, range: TextRange, inString: Boolean): KmetaSlot.TypeArgument? {
        val rule = when {
            MemberKind.ofKeyword(call.name) != null && call.argumentIndex == 1 -> call.name
            call.name == LIST && call.argumentIndex == 0 -> ruleOfList(call)
            else -> return null
        }
        val isProperty = rule?.let(MemberKind::ofKeyword) == MemberKind.PROPERTY
        return KmetaSlot.TypeArgument(isProperty, name, range, inString, listAllowed = call.name != LIST)
    }

    /**
     * The rule keyword of the call whose type argument the `list("…")` call [list] is, or null where the rule is
     * unknown. Inside `prop(` the list holds primitives; inside `has(`/`knows(` and wherever the rule is unknown it
     * holds types.
     */
    private fun ruleOfList(list: CallContext): String? =
        list.parent?.takeIf { MemberKind.ofKeyword(it.name) != null && it.argumentIndex == 1 }?.name

    /** The name `N` of a `type "N" "D"` block header, or null for other headers. */
    private fun typeNameOf(header: List<ContextToken>): String? {
        val type = header.indexOfLast { it.kind == TokenKind.KEYWORD && it.text == TYPE }
        return header.getOrNull(type + 1)?.takeIf { type >= 0 && it.kind == TokenKind.STRING }?.text
    }

    private companion object {
        const val TYPE = "type"
        const val LIST = "list"
    }
}
