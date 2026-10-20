package io.karpfen.ide.core.lang.kstates

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.modelpath.ModelPathResolver

/** The KStates provider family (concrete factory, spec §1a); shared mechanics are injected. Wiring only. */
internal class KstatesLanguageFeatures(
    contexts: CaretContextDetector,
    paths: ModelPathResolver,
    override val highlight: HighlightProvider,
    override val brackets: BracketMatcher,
) : LanguageFeatures {
    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES
    override val completion: CompletionProvider = KstatesCompletionProvider(contexts, paths)
    override val navigation: NavigationProvider = KstatesNavigationProvider(paths)
    override val hover: HoverProvider = KstatesHoverProvider(paths)
}
