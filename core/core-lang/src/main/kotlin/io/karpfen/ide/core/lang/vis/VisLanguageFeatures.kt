/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.core.lang.vis

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.feature.NavigationProvider

/**
 * The features of diagram layouts, the `.vis` companion files. Such a file is shown read-only, so there is nothing to
 * complete, and it names nothing a user would navigate to or ask about. The shared [highlight] provider styles it,
 * and the shared [brackets] matcher pairs its braces by their tokens.
 */
internal class VisLanguageFeatures(
    override val highlight: HighlightProvider,
    override val brackets: BracketMatcher,
) : LanguageFeatures {
    override val language: KarpfenLanguage = KarpfenLanguage.VIS
    override val completion: CompletionProvider = CompletionProvider { _, _ -> emptyList() }
    override val navigation: NavigationProvider = NoNavigation
    override val hover: HoverProvider = HoverProvider { _, _ -> null }

    /** A [NavigationProvider] that finds no definition and no reference. */
    private object NoNavigation : NavigationProvider {
        override fun definition(request: FeatureRequest, offset: Int): List<Location> = emptyList()

        override fun references(request: FeatureRequest, offset: Int): List<Location> = emptyList()
    }
}
