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
import io.karpfen.ide.core.lang.navigation.ContextReferences
import io.karpfen.ide.core.lang.symbols.TypeCatalog

/**
 * The KStates provider family (a concrete factory of [LanguageFeatures]); shared mechanics are injected, among them the
 * path scopes ([KstatesPathScopes]), which the cross-file references of the context use as well. Wiring only.
 */
internal class KstatesLanguageFeatures(
    caretContexts: CaretContextDetector,
    types: TypeCatalog,
    paths: ModelPathResolver,
    scopes: KstatesPathScopes,
    references: ContextReferences,
    override val highlight: HighlightProvider,
    override val brackets: BracketMatcher,
) : LanguageFeatures {
    private val slots = KstatesSlots(caretContexts)
    private val kstatesPaths = KstatesPaths(scopes, paths)

    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES
    override val completion: CompletionProvider = KstatesCompletionProvider(slots, kstatesPaths, types)
    override val navigation: NavigationProvider = KstatesNavigationProvider(slots, kstatesPaths, types, references)
    override val hover: HoverProvider = KstatesHoverProvider(slots, kstatesPaths, types)
}
