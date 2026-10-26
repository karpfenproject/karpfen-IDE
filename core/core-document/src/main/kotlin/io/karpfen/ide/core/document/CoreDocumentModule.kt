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
 * Identity of the `core-document` module, kept from the phase-0 skeleton: tokens, parse outcomes and diagnostics of a
 * document, on top of karpfen-dsl-tools for the modeling languages and of the IDE's own JSON parser for environment
 * descriptors, kept up to date by a document session. Start at `DocumentSession` (package `session`) and
 * `LanguageSupport` (package `language`); the module list of the README places the module among the others.
 */
public object CoreDocumentModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "core-document"
}
