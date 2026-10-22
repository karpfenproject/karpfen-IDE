package io.karpfen.ide.core.document.language

import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * Resolves the [LanguageSupport] for a language or file name. The built-in registry of the three karpfen
 * languages is created by `io.karpfen.ide.core.document.registry.defaultLanguageRegistry()`; tests build registries
 * from fakes with the [LanguageRegistry] factory function.
 */
public interface LanguageRegistry {

    /** The registered languages. */
    public val languages: Set<KarpfenLanguage>

    /**
     * The support for [language].
     * @throws IllegalArgumentException if [language] is not registered (programming error).
     */
    public fun support(language: KarpfenLanguage): LanguageSupport

    /**
     * The language of a file named [fileName] (`KarpfenLanguage.fromFileName`, the one file-name rule) if it is
     * registered, else null.
     */
    public fun languageOf(fileName: String): KarpfenLanguage?

    /** The support for a file named [fileName], or null if its language is not registered. */
    public fun supportFor(fileName: String): LanguageSupport? = languageOf(fileName)?.let(::support)
}

/**
 * A registry of [supports].
 * @throws IllegalArgumentException if two supports declare the same language.
 */
public fun LanguageRegistry(supports: Collection<LanguageSupport>): LanguageRegistry {
    val byLanguage = supports.associateBy { it.language }
    require(byLanguage.size == supports.size) { "duplicate languages in ${supports.map { it.language }}" }
    return MapLanguageRegistry(byLanguage)
}

/** Supports by language; file names are resolved with `KarpfenLanguage.fromFileName`. */
private class MapLanguageRegistry(private val byLanguage: Map<KarpfenLanguage, LanguageSupport>) : LanguageRegistry {

    override val languages: Set<KarpfenLanguage> = byLanguage.keys

    override fun support(language: KarpfenLanguage): LanguageSupport =
        requireNotNull(byLanguage[language]) { "language $language is not registered (registered: $languages)" }

    override fun languageOf(fileName: String): KarpfenLanguage? =
        KarpfenLanguage.fromFileName(fileName)?.takeIf { it in byLanguage }
}
