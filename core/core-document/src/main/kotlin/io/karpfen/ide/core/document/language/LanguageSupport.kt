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
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.syntax.Tokenizer

/**
 * Everything core-document knows about one language: its [tokenizer], its [parser] and its [lineComment]. Each
 * support is the product family of an Abstract Factory for one language. Adding a language adds a support and changes
 * no existing language class.
 *
 * The product family is split by layer. The editor features of a language live in core-lang as `LanguageFeatures`,
 * registered by `karpfenLanguageFeatures()`. This keeps core-document free of project knowledge. A test in core-lang
 * checks that every registered language has features.
 */
public interface LanguageSupport {
    /**
     * The supported language. File names map to languages by one closed rule, `KarpfenLanguage.fromFileName`, which
     * matches the extension of each language. The registry and `FileKind` in core-workspace both use it, so the two
     * never disagree about a file.
     */
    public val language: KarpfenLanguage

    /** The tokenizer for highlighting. It never throws. */
    public val tokenizer: Tokenizer

    /** The parser for the outline, the index, folding, semantic spans and standalone semantics. It never throws. */
    public val parser: DocumentParser

    /**
     * The marker of a line comment, which the toggle comment action inserts and removes. It is null for a language
     * without comments, such as the JSON of environment descriptors. Every language states its own marker, so the
     * editor never comments a language with the marker of another.
     */
    public val lineComment: String?
}

/** A [LanguageSupport] made of the given parts. Tests use it, and so do languages contributed from outside. */
public fun LanguageSupport(
    language: KarpfenLanguage,
    tokenizer: Tokenizer,
    parser: DocumentParser,
    lineComment: String?,
): LanguageSupport = SimpleLanguageSupport(language, tokenizer, parser, lineComment)

/** The [LanguageSupport] made of its given parts, behind the factory function of the same name. */
private data class SimpleLanguageSupport(
    override val language: KarpfenLanguage,
    override val tokenizer: Tokenizer,
    override val parser: DocumentParser,
    override val lineComment: String?,
) : LanguageSupport
