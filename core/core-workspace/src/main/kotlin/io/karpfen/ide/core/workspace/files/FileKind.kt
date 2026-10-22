package io.karpfen.ide.core.workspace.files

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.DescriptorFileRole

/**
 * Role of a file in a workspace, derived from its name only.
 *
 * @property language the karpfen language of the file, or null for files the IDE does not edit.
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
    ENV_DESCRIPTOR(KarpfenLanguage.ENVIRONMENT),

    /** A bridge configuration (`*.kbridge.json`), named by environment descriptors; not interpreted yet. */
    BRIDGE(null),

    /** A diagram layout sidecar (`*.layout.json`). */
    LAYOUT(null),

    /** Anything else. */
    OTHER(null),
    ;

    /**
     * True for the files of the modeling languages (KMeta, KModel, KStates): the files that form modeling contexts and
     * that the project analysis tokenizes, parses and converts. Environment descriptors are edited with full language
     * support as well, but they define contexts instead of belonging to one.
     */
    public val isKarpfenSource: Boolean get() = language?.isModelingLanguage == true

    /**
     * True if a descriptor key of [role] may name a file of this kind: a file in the role's language
     * ([DescriptorFileRole.language]) or, for a role without one, any file. The workspace checks a descriptor's
     * references by this rule, and completion offers the files it accepts.
     */
    public fun fits(role: DescriptorFileRole): Boolean = role.language == null || language == role.language

    /**
     * True if a file of this kind is the usual choice for [role], which completion offers before the other fitting
     * files: the event metamodel `EVENTS.kmeta` for the events, any other metamodel for the domain metamodel, a bridge
     * configuration for a bridge, and every fitting file for the model and the state machines. Other names are allowed;
     * the runtime only conventionally names the event metamodel `EVENTS.kmeta`.
     */
    public fun isUsualFor(role: DescriptorFileRole): Boolean = when (role) {
        DescriptorFileRole.METAMODEL -> this == KMETA
        DescriptorFileRole.EVENTS -> this == EVENTS_KMETA
        DescriptorFileRole.BRIDGE -> this == BRIDGE
        DescriptorFileRole.MODEL, DescriptorFileRole.STATE_MACHINE -> fits(role)
    }

    public companion object {
        /** File-name suffix of environment descriptors. */
        public val DESCRIPTOR_SUFFIX: String = ".${KarpfenLanguage.ENVIRONMENT.extension}"

        /** File-name suffix of bridge configurations. */
        public const val BRIDGE_SUFFIX: String = ".kbridge.json"

        /** File-name suffix of layout sidecars. */
        public const val LAYOUT_SUFFIX: String = ".layout.json"

        /** Name of the event metamodel. */
        public const val EVENTS_FILE_NAME: String = "EVENTS.kmeta"

        /**
         * The kind of a file named [fileName] (a name, not a path); suffixes are compared ignoring case. Languages come
         * from `KarpfenLanguage.fromFileName`, the one file-name rule that `LanguageRegistry.languageOf` uses too.
         */
        public fun of(fileName: String): FileKind = when {
            fileName.equals(EVENTS_FILE_NAME, ignoreCase = true) -> EVENTS_KMETA
            fileName.endsWith(BRIDGE_SUFFIX, ignoreCase = true) -> BRIDGE
            fileName.endsWith(LAYOUT_SUFFIX, ignoreCase = true) -> LAYOUT
            else -> when (KarpfenLanguage.fromFileName(fileName)) {
                KarpfenLanguage.KMETA -> KMETA
                KarpfenLanguage.KMODEL -> KMODEL
                KarpfenLanguage.KSTATES -> KSTATES
                KarpfenLanguage.ENVIRONMENT -> ENV_DESCRIPTOR
                null -> OTHER
            }
        }
    }
}
