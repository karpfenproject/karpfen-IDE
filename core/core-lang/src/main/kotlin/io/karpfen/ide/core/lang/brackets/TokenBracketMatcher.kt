package io.karpfen.ide.core.lang.brackets

import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.FeatureRequest

/**
 * Matches `{}` and `()` over PUNCTUATION tokens (brackets inside strings, comments and embedded code are ignored),
 * preferring the bracket right of the offset, then the one left of it.
 */
internal class TokenBracketMatcher : BracketMatcher {
    override fun match(request: FeatureRequest, offset: Int): BracketMatch? =
        TODO("WP-LANG: match brackets on tokens")
}
