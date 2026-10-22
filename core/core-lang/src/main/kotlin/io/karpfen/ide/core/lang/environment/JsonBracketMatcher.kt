package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.json.JsonTokenKind
import io.karpfen.ide.core.document.json.containers
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.text.TextRange

/**
 * Bracket matching in JSON: `{}` and `[]` pair as the parser closed them ([containers], the structure of the tree
 * whatever its values mean), so brackets inside strings never pair and a bracket the text does not close has no match.
 * The bracket right of the offset is preferred, then the one left of it.
 */
internal class JsonBracketMatcher(private val trees: DescriptorTrees) : BracketMatcher {

    override fun match(request: FeatureRequest, offset: Int): BracketMatch? {
        val document = trees.of(request.document.text)
        val root = document.root ?: return null
        val candidates = listOfNotNull(document.tokenAt(offset), document.tokenBefore(offset)?.takeIf { it.range.end == offset })
            .filter { it.kind in BRACKETS }
        if (candidates.isEmpty()) return null
        val containers = root.containers().filter { it.isClosed }
        for (bracket in candidates) {
            val container = containers.firstOrNull { it.range.start == bracket.range.start || it.range.end == bracket.range.end }
            if (container != null) {
                return BracketMatch(TextRange.ofLength(container.range.start, 1), TextRange(container.range.end - 1, container.range.end))
            }
        }
        return null
    }

    private companion object {
        /** The bracket tokens of JSON. */
        val BRACKETS = setOf(JsonTokenKind.BEGIN_OBJECT, JsonTokenKind.END_OBJECT, JsonTokenKind.BEGIN_ARRAY, JsonTokenKind.END_ARRAY)
    }
}
