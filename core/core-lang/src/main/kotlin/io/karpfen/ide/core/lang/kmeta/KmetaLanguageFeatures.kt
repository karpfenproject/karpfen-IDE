package io.karpfen.ide.core.lang.kmeta

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

/** The KMeta provider family (a concrete factory of [LanguageFeatures]); shared mechanics are injected. Wiring only. */
internal class KmetaLanguageFeatures(
    caretContexts: CaretContextDetector,
    types: TypeCatalog,
    references: ContextReferences,
    override val highlight: HighlightProvider,
    override val brackets: BracketMatcher,
) : LanguageFeatures {
    private val slots = KmetaSlots(caretContexts)

    override val language: KarpfenLanguage = KarpfenLanguage.KMETA
    override val completion: CompletionProvider = KmetaCompletionProvider(slots, types)
    override val navigation: NavigationProvider = KmetaNavigationProvider(slots, types, references)
    override val hover: HoverProvider = KmetaHoverProvider(slots, types)
}
