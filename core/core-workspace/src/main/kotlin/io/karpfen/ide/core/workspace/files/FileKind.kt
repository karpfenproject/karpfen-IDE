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
package io.karpfen.ide.core.workspace.files

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.DescriptorFileRole

/**
 * The role of a file in a workspace, derived from its name only.
 *
 * @property language the karpfen language of the file, or null for files the IDE does not edit.
 */
public enum class FileKind(public val language: KarpfenLanguage?) {
    /** A domain metamodel, any `*.kmeta` file except `EVENTS.kmeta`. */
    KMETA(KarpfenLanguage.KMETA),

    /** The event metamodel `EVENTS.kmeta`. The name is compared ignoring case. */
    EVENTS_KMETA(KarpfenLanguage.KMETA),

    /** A model, `*.kmodel`. */
    KMODEL(KarpfenLanguage.KMODEL),

    /** A state machine, `*.kstates`. */
    KSTATES(KarpfenLanguage.KSTATES),

    /** An environment descriptor, `*.kenv.json`. */
    ENV_DESCRIPTOR(KarpfenLanguage.ENVIRONMENT),

    /**
     * A bridge configuration, `*.kbridge.json`. Environment descriptors name such files, but the IDE does not interpret
     * them yet.
     */
    BRIDGE(null),

    /** A diagram layout sidecar, `*.layout.json`. */
    LAYOUT(null),

    /** Anything else. */
    OTHER(null),
    ;

    /**
     * True for the files of the modeling languages KMeta, KModel and KStates. These files form modeling contexts, and
     * the project analysis tokenizes, parses and reads them with dsl-tools. Environment descriptors are edited with
     * full language support as well, but they define contexts instead of belonging to one.
     */
    public val isKarpfenSource: Boolean get() = language?.isModelingLanguage == true

    /**
     * True if a descriptor key of [role] may name a file of this kind. That is a file in the language of the role,
     * given by [DescriptorFileRole.language], or any file for a role without a language. The workspace checks the
     * references of a descriptor by this rule, and completion offers the files it accepts.
     */
    public fun fits(role: DescriptorFileRole): Boolean = role.language == null || language == role.language

    /**
     * True if a file of this kind is the usual choice for [role]. Completion offers such files before the other fitting
     * files. The usual choice is `EVENTS.kmeta` for the events, any other metamodel for the domain metamodel, a bridge
     * configuration for a bridge, and every fitting file for the model and the state machines. Other names are
     * allowed. Naming the event metamodel `EVENTS.kmeta` is only a convention of the runtime.
     */
    public fun isUsualFor(role: DescriptorFileRole): Boolean = when (role) {
        DescriptorFileRole.METAMODEL -> this == KMETA
        DescriptorFileRole.EVENTS -> this == EVENTS_KMETA
        DescriptorFileRole.BRIDGE -> this == BRIDGE
        DescriptorFileRole.MODEL, DescriptorFileRole.STATE_MACHINE -> fits(role)
    }

    public companion object {
        /** The file-name suffix of environment descriptors. */
        public val DESCRIPTOR_SUFFIX: String = ".${KarpfenLanguage.ENVIRONMENT.extension}"

        /** The file-name suffix of bridge configurations. */
        public const val BRIDGE_SUFFIX: String = ".kbridge.json"

        /** The file-name suffix of layout sidecars. */
        public const val LAYOUT_SUFFIX: String = ".layout.json"

        /** The name of the event metamodel. */
        public const val EVENTS_FILE_NAME: String = "EVENTS.kmeta"

        /**
         * The kind of a file named [fileName], which is a name and not a path. Suffixes are compared ignoring case. The
         * languages come from `KarpfenLanguage.fromFileName`, the same file-name rule that
         * `LanguageRegistry.languageOf` uses.
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
