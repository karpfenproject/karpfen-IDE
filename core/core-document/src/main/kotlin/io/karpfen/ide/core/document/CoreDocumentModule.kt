package io.karpfen.ide.core.document

/**
 * Identity of the `core-document` module, kept from the phase-0 skeleton: tokens, parse outcomes and diagnostics of a
 * document, on top of karpfen-dsl-tools for the modeling languages and of the IDE's own JSON parser for environment
 * descriptors, kept up to date by a document session. Start at `DocumentSession` (package `session`) and
 * `LanguageSupport` (package `language`); the module list of the README places the module among the others.
 */
public object CoreDocumentModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "core-document"
}
