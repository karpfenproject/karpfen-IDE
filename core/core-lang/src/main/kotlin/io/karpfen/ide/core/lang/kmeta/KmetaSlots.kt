package io.karpfen.ide.core.lang.kmeta

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.context.CallContext
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.text.TextRange

/** What the caret is on or in, in a KMeta document (spec §5); the one classification of completion, hover, navigation. */
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
     * [inString] is false when the caret is at the argument but not inside quotes yet ([name] is then empty).
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

    private fun classify(document: LanguageDocument, context: CaretContext): KmetaSlot {
        if (context.inComment) return KmetaSlot.None
        val owner = context.blocks.asReversed().firstNotNullOfOrNull { typeNameOf(it.header) }
        val call = context.call
        val string = context.string
        if (string != null) {
            val range = if (string.closed) string.contentRange else context.replaceRange
            val name = document.text.substring(range)
            val afterTypeKeyword = context.statement.lastOrNull()?.let { it.kind == TokenKind.KEYWORD && it.text == TYPE } == true
            return when {
                call == null && afterTypeKeyword -> KmetaSlot.TypeName(name, range)
                call == null -> KmetaSlot.None
                call.name in RULES && call.argumentIndex == 0 -> KmetaSlot.MemberName(owner, RULES.getValue(call.name), name, range)
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

    private fun typeArgument(call: CallContext, name: String, range: TextRange, inString: Boolean): KmetaSlot.TypeArgument? {
        val rule = when {
            call.name in RULES && call.argumentIndex == 1 -> call.name
            // `list("…")`: primitives inside `prop(`, types inside `has(`/`knows(` and wherever the rule is unknown.
            call.name == LIST && call.argumentIndex == 0 -> call.parent?.takeIf { it.name in RULES && it.argumentIndex == 1 }?.name
            else -> return null
        }
        return KmetaSlot.TypeArgument(rule == PROP, name, range, inString, listAllowed = call.name != LIST)
    }

    /** The name `N` of a `type "N" "D"` block header, or null for other headers. */
    private fun typeNameOf(header: List<ContextToken>): String? {
        val type = header.indexOfLast { it.kind == TokenKind.KEYWORD && it.text == TYPE }
        return header.getOrNull(type + 1)?.takeIf { type >= 0 && it.kind == TokenKind.STRING }?.text
    }

    private companion object {
        const val TYPE = "type"
        const val PROP = "prop"
        const val LIST = "list"
        val RULES = mapOf(PROP to MemberKind.PROPERTY, "has" to MemberKind.HAS, "knows" to MemberKind.KNOWS)
    }
}
