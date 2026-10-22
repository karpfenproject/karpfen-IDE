package io.karpfen.ide.core.lang

/**
 * Identity of the `core-lang` module, kept from the phase-0 skeleton: the language services (highlighting, completion,
 * navigation, hover, bracket matching) over the documents and the workspace's project analysis. The module map of the
 * architecture proposal (`docs/architecture-proposal.md`) shows where the module sits among the others.
 */
public object CoreLangModule {
    /** The module's name in the module map. */
    public const val ID: String = "core-lang"
}
