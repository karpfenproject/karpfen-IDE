package io.karpfen.ide.core.lang.highlight

import io.karpfen.ide.core.document.parse.SemanticKind
import io.karpfen.ide.core.document.syntax.TokenKind
import io.karpfen.ide.core.lang.HighlightSpan
import io.karpfen.ide.core.lang.HighlightStyle
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.overlayHighlights

/**
 * Language-neutral highlighting shared by all languages: maps every `TokenKind` to a `HighlightStyle` and, when the
 * parse outcome has the tokens' version, overlays its `SemanticSpan`s (`SemanticKind` → the `HighlightStyle` of the
 * same name, all `isSemantic`) with `overlayHighlights` (splitting lexical spans where a semantic span covers part of
 * them, e.g. the name inside a string's quotes). Result sorted and non-overlapping. Stateless.
 *
 * Version handling: the semantic layer comes from `document.parse`, or from `document.lastGoodParse` if only that one
 * has the tokens' version; an outcome of any other version contributes nothing (its ranges are in other coordinates —
 * the editor carries the previous semantic layer forward itself, so the semantic colours do not flicker).
 */
internal class DefaultHighlightProvider : HighlightProvider {

    override fun highlight(request: FeatureRequest): List<HighlightSpan> {
        val document = request.document
        val lexical = document.tokens.tokens.map { HighlightSpan(it.range, styleOf(it.kind)) }
        val parse = listOfNotNull(document.parse, document.lastGoodParse).firstOrNull { it.version == document.tokens.version }
            ?: return lexical
        return overlayHighlights(lexical, parse.semanticSpans.map { HighlightSpan(it.range, styleOf(it.kind)) })
    }

    internal companion object {
        /** The lexical style of a token kind (one style per kind, same name). */
        fun styleOf(kind: TokenKind): HighlightStyle = when (kind) {
            TokenKind.KEYWORD -> HighlightStyle.KEYWORD
            TokenKind.OPERATION -> HighlightStyle.OPERATION
            TokenKind.STRING -> HighlightStyle.STRING
            TokenKind.NUMBER -> HighlightStyle.NUMBER
            TokenKind.IDENTIFIER -> HighlightStyle.IDENTIFIER
            TokenKind.PUNCTUATION -> HighlightStyle.PUNCTUATION
            TokenKind.OPERATOR -> HighlightStyle.OPERATOR
            TokenKind.COMMENT -> HighlightStyle.COMMENT
            TokenKind.EMBEDDED_CODE -> HighlightStyle.EMBEDDED_CODE
            TokenKind.EMBEDDED_KEYWORD -> HighlightStyle.EMBEDDED_KEYWORD
            TokenKind.EMBEDDED_COMMENT -> HighlightStyle.EMBEDDED_COMMENT
            TokenKind.EMBEDDED_STRING -> HighlightStyle.EMBEDDED_STRING
            TokenKind.EMBEDDED_NUMBER -> HighlightStyle.EMBEDDED_NUMBER
            TokenKind.PATH_REFERENCE -> HighlightStyle.PATH_REFERENCE
            TokenKind.MACRO_REFERENCE -> HighlightStyle.MACRO_REFERENCE
            TokenKind.ERROR -> HighlightStyle.ERROR
        }

        /** The semantic style of a semantic kind (one style per kind, same name; all `isSemantic`). */
        fun styleOf(kind: SemanticKind): HighlightStyle = when (kind) {
            SemanticKind.TYPE_NAME -> HighlightStyle.TYPE_NAME
            SemanticKind.PRIMITIVE_TYPE -> HighlightStyle.PRIMITIVE_TYPE
            SemanticKind.PROPERTY_NAME -> HighlightStyle.PROPERTY_NAME
            SemanticKind.OBJECT_NAME -> HighlightStyle.OBJECT_NAME
            SemanticKind.STATE_NAME -> HighlightStyle.STATE_NAME
            SemanticKind.MACRO_NAME -> HighlightStyle.MACRO_NAME
            SemanticKind.EVENT_NAME -> HighlightStyle.EVENT_NAME
            SemanticKind.EVENT_DOMAIN -> HighlightStyle.EVENT_DOMAIN
            SemanticKind.MODEL_PATH -> HighlightStyle.MODEL_PATH
            SemanticKind.DESCRIPTION -> HighlightStyle.DESCRIPTION
            SemanticKind.BINDING_NAME -> HighlightStyle.BINDING_NAME
            SemanticKind.FILE_PATH -> HighlightStyle.FILE_PATH
        }
    }
}
