package io.karpfen.ide.core.lang.kmodel

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.context.CaretContextDetector
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.feature.NavigationProvider
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/** The KModel provider family (concrete factory, spec §1a); shared mechanics are injected. Wiring only. */
internal class KmodelLanguageFeatures(
    caretContexts: CaretContextDetector,
    types: TypeCatalog,
    references: ContextReferences,
    override val highlight: HighlightProvider,
    override val brackets: BracketMatcher,
) : LanguageFeatures {
    private val slots = KmodelSlots(caretContexts)

    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL
    override val completion: CompletionProvider = KmodelCompletionProvider(slots, types)
    override val navigation: NavigationProvider = KmodelNavigationProvider(slots, types, references)
    override val hover: HoverProvider = KmodelHoverProvider(slots, types)
}
