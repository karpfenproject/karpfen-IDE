package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.context.CaretContext
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.context.ContextToken
import io.karpfen.ide.core.lang.symbols.MemberKind
import io.karpfen.ide.core.text.TextRange

/** What the caret is on or in, in a KModel document; the one classification of completion, hover and navigation. */
internal sealed interface KmodelSlot {
    /** Before the root object: the `make object` snippet. */
    data object TopLevel : KmodelSlot

    /** In the body of an object of type [objectType], outside a statement: the statement snippets. */
    data class ObjectBody(val objectType: String?) : KmodelSlot

    /**
     * The key of `prop("k"` ([kind] PROPERTY), `has("k"` or `knows("k"` in the body of an object of type [objectType];
     * [inString] is false when the caret is in the parentheses but not inside quotes yet.
     */
    data class Key(val objectType: String?, val kind: MemberKind, val name: String, val range: TextRange, val inString: Boolean) :
        KmodelSlot

    /**
     * The type of `make object "k":"T"`; [relation] is the `has` relation of [ownerType] (the enclosing object's type)
     * the object is the value of, if any.
     */
    data class ObjectType(val name: String, val range: TextRange, val ownerType: String?, val relation: String?) : KmodelSlot

    /** The key of `make object "k"` (a declaration). */
    data class ObjectKey(val name: String, val range: TextRange) : KmodelSlot

    /** The target of `knows("r") -> "obj"`: [relation] `r` of [ownerType] (the enclosing object's type). */
    data class ObjectReference(val name: String, val range: TextRange, val ownerType: String?, val relation: String) : KmodelSlot

    /** Anything else (values, comments, broken positions). */
    data object None : KmodelSlot
}

/**
 * Classifies the caret of a KModel document from its [CaretContext]. The enclosing object's type comes from the header
 * of the innermost `make object "k":"T" {` block. Stateless.
 */
internal class KmodelSlots(private val caretContexts: CaretContextDetector) {

    /** The caret context at [offset] and its slot. */
    fun at(document: LanguageDocument, offset: Int): Pair<CaretContext, KmodelSlot> {
        val context = caretContexts.detect(document, offset)
        return context to classify(document, context)
    }

    /** The slot of [context]: keys by the call around the caret, other strings by the statement before them. */
    private fun classify(document: LanguageDocument, context: CaretContext): KmodelSlot {
        if (context.inComment) return KmodelSlot.None
        val objectType = context.blocks.asReversed().firstNotNullOfOrNull { objectTypeOf(it.header) }
        val call = context.call
        val string = context.string
        val statement = context.statement
        if (call != null) {
            val kind = MemberKind.ofKeyword(call.name)?.takeIf { call.argumentIndex == 0 } ?: return KmodelSlot.None
            if (string == null) return KmodelSlot.Key(objectType, kind, "", context.replaceRange, inString = false)
            val range = if (string.closed) string.contentRange else context.replaceRange
            return KmodelSlot.Key(objectType, kind, document.text.substring(range), range, inString = true)
        }
        if (string == null) {
            return when {
                context.blocks.isEmpty() -> if (statement.isEmpty()) KmodelSlot.TopLevel else KmodelSlot.None
                objectType != null && statement.endsStatement() -> KmodelSlot.ObjectBody(objectType)
                else -> KmodelSlot.None
            }
        }
        val range = if (string.closed) string.contentRange else context.replaceRange
        val name = document.text.substring(range)
        val knows = statement.relationBefore(statement.size, "knows")
        return when {
            statement.endsWith(MAKE, OBJECT) -> KmodelSlot.ObjectKey(name, range)
            statement.endsWith(MAKE, OBJECT, null, ":") ->
                KmodelSlot.ObjectType(name, range, objectType, statement.relationBefore(statement.size - 4, "has"))
            knows != null -> KmodelSlot.ObjectReference(name, range, objectType, knows)
            else -> KmodelSlot.None
        }
    }

    /** The relation key of `keyword("k") ->` ending right before [end] in this statement, or null. */
    private fun List<ContextToken>.relationBefore(end: Int, keyword: String): String? {
        val tokens = subList(0, end.coerceIn(0, size))
        return if (tokens.endsWith(keyword, "(", null, ")", "->")) tokens[tokens.size - 3].text else null
    }

    /** True if the statement is empty or a complete statement (`… -> "v"` or `… }`) precedes the caret. */
    private fun List<ContextToken>.endsStatement(): Boolean =
        isEmpty() || (last().kind == TokenKind.STRING && getOrNull(size - 2)?.text == "->")

    /** True if the statement ends with tokens of these texts; null matches one string literal. */
    private fun List<ContextToken>.endsWith(vararg texts: String?): Boolean {
        if (size < texts.size) return false
        return texts.withIndex().all { (i, text) ->
            val token = this[size - texts.size + i]
            if (text == null) token.kind == TokenKind.STRING else token.kind != TokenKind.STRING && token.text == text
        }
    }

    private companion object {
        const val MAKE = "make"
        const val OBJECT = "object"

        /** The type `T` of a `make object "k":"T"` block header, or null for other headers. */
        fun objectTypeOf(header: List<ContextToken>): String? {
            val make = header.indexOfLast { it.kind == TokenKind.KEYWORD && it.text == MAKE }
            if (make < 0 || header.getOrNull(make + 1)?.text != OBJECT || header.getOrNull(make + 3)?.text != ":") return null
            return header.getOrNull(make + 4)?.takeIf { it.kind == TokenKind.STRING }?.text
        }
    }
}
