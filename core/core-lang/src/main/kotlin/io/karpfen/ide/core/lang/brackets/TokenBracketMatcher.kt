package io.karpfen.ide.core.lang.brackets

import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.LanguageDocument
import io.karpfen.ide.core.lang.feature.BracketMatcher

/**
 * Matches `{}` and `()` over PUNCTUATION tokens (brackets inside strings, comments and embedded code are ignored),
 * preferring the bracket right of the offset, then the one left of it.
 */
internal class TokenBracketMatcher : BracketMatcher {
    override fun match(document: LanguageDocument, offset: Int): BracketMatch? =
        TODO("WP-LANG: match brackets on tokens")
}
