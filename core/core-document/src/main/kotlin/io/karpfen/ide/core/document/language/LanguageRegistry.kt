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
package io.karpfen.ide.core.document.language

import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * Resolves the [LanguageSupport] for a language or a file name. The registry of the built-in karpfen languages is
 * created by `io.karpfen.ide.core.document.registry.defaultLanguageRegistry()`. Tests build registries from fakes
 * with the [LanguageRegistry] factory function.
 */
public interface LanguageRegistry {

    /** The registered languages. */
    public val languages: Set<KarpfenLanguage>

    /**
     * The support for [language].
     * @throws IllegalArgumentException if [language] is not registered. That is a programming error.
     */
    public fun support(language: KarpfenLanguage): LanguageSupport

    /**
     * The language of a file named [fileName] if that language is registered, else null. The language comes from
     * `KarpfenLanguage.fromFileName`, the only rule that maps file names to languages.
     */
    public fun languageOf(fileName: String): KarpfenLanguage?

    /** The support for a file named [fileName], or null if its language is not registered. */
    public fun supportFor(fileName: String): LanguageSupport? = languageOf(fileName)?.let(::support)
}

/**
 * A registry that holds [supports].
 * @throws IllegalArgumentException if two supports declare the same language.
 */
public fun LanguageRegistry(supports: Collection<LanguageSupport>): LanguageRegistry {
    val byLanguage = supports.associateBy { it.language }
    require(byLanguage.size == supports.size) { "duplicate languages in ${supports.map { it.language }}" }
    return MapLanguageRegistry(byLanguage)
}

/** Holds the supports by language. It resolves file names with `KarpfenLanguage.fromFileName`. */
private class MapLanguageRegistry(private val byLanguage: Map<KarpfenLanguage, LanguageSupport>) : LanguageRegistry {

    override val languages: Set<KarpfenLanguage> = byLanguage.keys

    override fun support(language: KarpfenLanguage): LanguageSupport =
        requireNotNull(byLanguage[language]) { "language $language is not registered (registered: $languages)" }

    override fun languageOf(fileName: String): KarpfenLanguage? =
        KarpfenLanguage.fromFileName(fileName)?.takeIf { it in byLanguage }
}
