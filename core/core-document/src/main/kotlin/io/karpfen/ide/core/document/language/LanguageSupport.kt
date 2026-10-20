package io.karpfen.ide.core.document.language

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.syntax.Tokenizer

/**
 * Everything core-document knows about one language (Abstract Factory product family, spec §1a): its [tokenizer] and
 * its [parser]. The product family is split by layer: the editor features of a language live in core-lang
 * (`LanguageFeatures`, registered by `karpfenLanguageFeatures()`), so core-document stays free of project knowledge;
 * a test in core-lang checks that every registered language has features.
 */
public interface LanguageSupport {
    /**
     * The supported language. File names map to languages by one closed rule, `KarpfenLanguage.fromFileName` (the
     * enum's extension), used by the registry and by core-workspace's `FileKind` alike (decision 20 of the
     * work-package plan).
     */
    public val language: KarpfenLanguage

    /** Tokenizer for highlighting (never throws). */
    public val tokenizer: Tokenizer

    /** Parser for outline, index, folding, semantic spans and standalone semantics (never throws). */
    public val parser: DocumentParser
}

/** A [LanguageSupport] made of the given parts (for tests and for languages contributed from outside). */
public fun LanguageSupport(language: KarpfenLanguage, tokenizer: Tokenizer, parser: DocumentParser): LanguageSupport =
    SimpleLanguageSupport(language, tokenizer, parser)

private data class SimpleLanguageSupport(
    override val language: KarpfenLanguage,
    override val tokenizer: Tokenizer,
    override val parser: DocumentParser,
) : LanguageSupport
