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
package io.karpfen.ide.core.document

/**
 * The karpfen languages. Each is supported by one `LanguageSupport` bundle
 * (see [io.karpfen.ide.core.document.language.LanguageRegistry]).
 *
 * @property extension the file-name suffix without its leading dot; it may span several dots (`kenv.json`).
 * @property displayName name shown in the UI (status bar, dialogs).
 * @property isModelingLanguage true for the languages whose files form modeling contexts and are analyzed with
 *   karpfen-dsl-tools (KMeta, KModel, KStates); false for environment descriptors, which define modeling contexts
 *   instead of belonging to one. Every language states it, so a new one is never taken for a modeling language by
 *   default.
 */
public enum class KarpfenLanguage(
    public val extension: String,
    public val displayName: String,
    public val isModelingLanguage: Boolean,
) {
    /** Metamodels (`.kmeta`), including the event metamodel `EVENTS.kmeta`. */
    KMETA("kmeta", "KMeta", isModelingLanguage = true),

    /** Model instances (`.kmodel`). */
    KMODEL("kmodel", "KModel", isModelingLanguage = true),

    /** Hierarchical state machines with embedded Python (`.kstates`). */
    KSTATES("kstates", "KStates", isModelingLanguage = true),

    /**
     * Environment descriptors (`.kenv.json`): JSON files that name the files of one runnable modeling context and how
     * the runtime runs them. Their keys are described by `EnvironmentSchema` of the `environment` package.
     */
    ENVIRONMENT("kenv.json", "Environment", isModelingLanguage = false),
    ;

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
