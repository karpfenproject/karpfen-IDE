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
package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.EnvironmentSchema
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.lang.feature.BracketMatcher
import io.karpfen.ide.core.lang.feature.CompletionProvider
import io.karpfen.ide.core.lang.feature.HighlightProvider
import io.karpfen.ide.core.lang.feature.HoverProvider
import io.karpfen.ide.core.lang.feature.LanguageFeatures
import io.karpfen.ide.core.lang.feature.NavigationProvider

/**
 * The environment descriptor provider family (a concrete factory of [LanguageFeatures]): completion, hover and
 * navigation by the [schema] and the caret's [DescriptorSlot], JSON bracket matching, and the shared [highlight]
 * provider, which styles the semantic spans of the environment language's parse. The providers share one
 * [DescriptorTrees] and one [DescriptorCaret]. Wiring only.
 */
internal class EnvironmentLanguageFeatures(
    override val highlight: HighlightProvider,
    schema: ValueShape.Record = EnvironmentSchema.ROOT,
) : LanguageFeatures {
    private val trees = DescriptorTrees()
    private val caret = DescriptorCaret(schema)

    override val language: KarpfenLanguage = KarpfenLanguage.ENVIRONMENT
    override val completion: CompletionProvider = EnvironmentCompletionProvider(trees, caret, schema)
    override val navigation: NavigationProvider = EnvironmentNavigationProvider(trees, caret, schema)
    override val hover: HoverProvider = EnvironmentHoverProvider(trees, caret, schema)
    override val brackets: BracketMatcher = JsonBracketMatcher(trees)
}
