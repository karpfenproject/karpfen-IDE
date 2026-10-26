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
 * Everything core-document knows about one language, the product family of an Abstract Factory per language: its
 * [tokenizer], its [parser] and its [lineComment]. Adding a language adds a support and edits no existing language class. The product
 * family is split by layer: the editor features of a language live in core-lang
 * (`LanguageFeatures`, registered by `karpfenLanguageFeatures()`), so core-document stays free of project knowledge;
 * a test in core-lang checks that every registered language has features.
 */
public interface LanguageSupport {
    /**
     * The supported language. File names map to languages by one closed rule, `KarpfenLanguage.fromFileName` (the
     * enum's extension), used by the registry and by core-workspace's `FileKind` alike, so the two never disagree about
     * a file.
     */
    public val language: KarpfenLanguage

    /** Tokenizer for highlighting (never throws). */
    public val tokenizer: Tokenizer

    /** Parser for outline, index, folding, semantic spans and standalone semantics (never throws). */
    public val parser: DocumentParser

    /**
     * The marker of a line comment, which toggle comment inserts and removes, or null for a language without comments
     * (environment descriptors are JSON). Every language states its own, so the editor never comments a language with
     * the marker of another.
     */
    public val lineComment: String?
}

/** A [LanguageSupport] made of the given parts (for tests and for languages contributed from outside). */
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
