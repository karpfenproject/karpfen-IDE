package io.karpfen.ide.core.document

/**
 * The karpfen languages. Each is supported by one `LanguageSupport` bundle
 * (see [io.karpfen.ide.core.document.language.LanguageRegistry]).
 *
 * @property extension the file-name suffix without its leading dot; it may span several dots (`kenv.json`).
 * @property displayName name shown in the UI (status bar, dialogs).
 */
public enum class KarpfenLanguage(public val extension: String, public val displayName: String) {
    /** Metamodels (`.kmeta`), including the event metamodel `EVENTS.kmeta`. */
    KMETA("kmeta", "KMeta"),

    /** Model instances (`.kmodel`). */
    KMODEL("kmodel", "KModel"),

    /** Hierarchical state machines with embedded Python (`.kstates`). */
    KSTATES("kstates", "KStates"),

    /**
     * Environment descriptors (`.kenv.json`): JSON files that name the files of one runnable modeling context and how
     * the runtime runs them. Their keys are described by `EnvironmentSchema` of the `environment` package.
     */
    ENVIRONMENT("kenv.json", "Environment"),
    ;

    /**
     * True for KMeta, KModel and KStates, the languages whose files form modeling contexts and are analyzed with
     * karpfen-dsl-tools; false for environment descriptors, which define modeling contexts instead of belonging to one.
     */
    public val isModelingLanguage: Boolean get() = this != ENVIRONMENT

    public companion object {
        /**
         * The language of a file named [name] (a file name, not a path), matched by its [extension] after a dot,
         * ignoring case; null for any other file. `robot.kenv.json` is an environment descriptor, `robot.json` is
         * nothing.
         */
        public fun fromFileName(name: String): KarpfenLanguage? =
            entries.firstOrNull { language ->
                name.length > language.extension.length && name.endsWith(".${language.extension}", ignoreCase = true)
            }
    }
}
