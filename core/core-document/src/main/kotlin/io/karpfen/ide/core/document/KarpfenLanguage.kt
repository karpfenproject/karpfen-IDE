package io.karpfen.ide.core.document

/**
 * The karpfen languages. Each is supported by one `LanguageSupport` bundle
 * (see [io.karpfen.ide.core.document.language.LanguageRegistry]).
 *
 * @property extension file extension without the dot.
 * @property displayName name shown in the UI (status bar, dialogs).
 */
public enum class KarpfenLanguage(public val extension: String, public val displayName: String) {
    /** Metamodels (`.kmeta`), including the event metamodel `EVENTS.kmeta`. */
    KMETA("kmeta", "KMeta"),

    /** Model instances (`.kmodel`). */
    KMODEL("kmodel", "KModel"),

    /** Hierarchical state machines with embedded Python (`.kstates`). */
    KSTATES("kstates", "KStates"),
    ;

    public companion object {
        /**
         * The language of a file named [name] (a file name, not a path), matched by extension, ignoring case;
         * null for any other file.
         */
        public fun fromFileName(name: String): KarpfenLanguage? {
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return null
            val extension = name.substring(dot + 1)
            return entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
        }
    }
}
