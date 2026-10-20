package io.karpfen.ide.core.workspace.files

import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * Role of a file in a workspace (spec §4), derived from its name only.
 *
 * @property language the karpfen language of the file, or null for non-karpfen files.
 */
public enum class FileKind(public val language: KarpfenLanguage?) {
    /** A domain metamodel (`*.kmeta` except `EVENTS.kmeta`). */
    KMETA(KarpfenLanguage.KMETA),

    /** The event metamodel `EVENTS.kmeta` (name compared ignoring case). */
    EVENTS_KMETA(KarpfenLanguage.KMETA),

    /** A model (`*.kmodel`). */
    KMODEL(KarpfenLanguage.KMODEL),

    /** A state machine (`*.kstates`). */
    KSTATES(KarpfenLanguage.KSTATES),

    /** An environment descriptor (`*.kenv.json`). */
    ENV_DESCRIPTOR(null),

    /** A diagram layout sidecar (`*.layout.json`). */
    LAYOUT(null),

    /** Anything else. */
    OTHER(null),
    ;

    /** True for the karpfen languages (editable with full language support). */
    public val isKarpfenSource: Boolean get() = language != null

    public companion object {
        /** File-name suffix of environment descriptors. */
        public const val DESCRIPTOR_SUFFIX: String = ".kenv.json"

        /** File-name suffix of layout sidecars. */
        public const val LAYOUT_SUFFIX: String = ".layout.json"

        /** Name of the event metamodel. */
        public const val EVENTS_FILE_NAME: String = "EVENTS.kmeta"

        /** The kind of a file named [fileName] (a name, not a path); suffixes are compared ignoring case. */
        public fun of(fileName: String): FileKind = when {
            fileName.equals(EVENTS_FILE_NAME, ignoreCase = true) -> EVENTS_KMETA
            fileName.endsWith(DESCRIPTOR_SUFFIX, ignoreCase = true) -> ENV_DESCRIPTOR
            fileName.endsWith(LAYOUT_SUFFIX, ignoreCase = true) -> LAYOUT
            else -> when (KarpfenLanguage.fromFileName(fileName)) {
                KarpfenLanguage.KMETA -> KMETA
                KarpfenLanguage.KMODEL -> KMODEL
                KarpfenLanguage.KSTATES -> KSTATES
                null -> OTHER
            }
        }
    }
}
