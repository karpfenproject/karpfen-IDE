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
 * The karpfen languages. One `LanguageSupport` bundle supports each of them, and
 * [io.karpfen.ide.core.document.language.LanguageRegistry] holds the bundles.
 *
 * @property extension the file-name suffix without its leading dot. It may span several dots, as in `kenv.json`.
 * @property displayName name shown in the UI, for example in the status bar and in dialogs.
 * @property isModelingLanguage true for KMeta, KModel and KStates, whose files form modeling contexts and are analyzed
 *   with karpfen-dsl-tools. False for environment descriptors, which define modeling contexts instead of belonging to
 *   one. Every language states it explicitly, so a new language is never taken for a modeling language by default.
 */
public enum class KarpfenLanguage(
    public val extension: String,
    public val displayName: String,
    public val isModelingLanguage: Boolean,
) {
    /** Metamodels in `.kmeta` files, including the event metamodel `EVENTS.kmeta`. */
    KMETA("kmeta", "KMeta", isModelingLanguage = true),

    /** Model instances in `.kmodel` files. */
    KMODEL("kmodel", "KModel", isModelingLanguage = true),

    /** Hierarchical state machines with embedded Python in `.kstates` files. */
    KSTATES("kstates", "KStates", isModelingLanguage = true),

    /**
     * Environment descriptors in `.kenv.json` files. They are JSON files that name the files of one runnable modeling
     * context and say how the runtime runs them. `EnvironmentSchema` of the `environment` package describes their keys.
     */
    ENVIRONMENT("kenv.json", "Environment", isModelingLanguage = false),

    /**
     * Diagram layouts in `.vis` files. Such a file is the companion of a model file and holds, as JSON, where the
     * elements of its diagram are drawn. The IDE writes it and shows it read-only. It has no checks of its own.
     */
    VIS("vis", "Diagram Layout", isModelingLanguage = false),
    ;

    public companion object {
        /**
         * The language of a file named [name], which is a file name and not a path. The match is on [extension] after a
         * dot and ignores case. Returns null for any other file. `robot.kenv.json` is an environment descriptor, while
         * `robot.json` is nothing.
         */
        public fun fromFileName(name: String): KarpfenLanguage? =
            entries.firstOrNull { language ->
                name.length > language.extension.length && name.endsWith(".${language.extension}", ignoreCase = true)
            }
    }
}
