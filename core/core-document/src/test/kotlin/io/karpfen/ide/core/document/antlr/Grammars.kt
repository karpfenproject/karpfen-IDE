package io.karpfen.ide.core.document.antlr

import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * The grammar of the modeling language [language], for tests that iterate over the languages of example files.
 *
 * @throws NoSuchElementException for environment descriptors, which have no grammar.
 */
internal fun grammarOf(language: KarpfenLanguage): DslGrammar = DslGrammar.entries.single { it.language == language }
