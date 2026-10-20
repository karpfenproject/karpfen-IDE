package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.feature.NavigationProvider

/** The KModel provider family (concrete factory, spec §1a); shared mechanics are injected. Wiring only. */
internal class KmodelLanguageFeatures(
    contexts: CaretContextDetector,
    override val highlight: HighlightProvider,
    override val brackets: BracketMatcher,
) : LanguageFeatures {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL
    override val completion: CompletionProvider = KmodelCompletionProvider(contexts)
    override val navigation: NavigationProvider = KmodelNavigationProvider()
    override val hover: HoverProvider = KmodelHoverProvider()
}
