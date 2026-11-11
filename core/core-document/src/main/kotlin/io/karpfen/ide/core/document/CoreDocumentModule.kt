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
 * Identity of the `core-document` module. The module computes the tokens, parse outcomes and diagnostics of a
 * document. It builds on karpfen-dsl-tools for the modeling languages and on the IDE's own JSON parser for environment
 * descriptors. A document session keeps these results up to date. Start reading at `DocumentSession` in the package
 * `session` and at `LanguageSupport` in the package `language`.
 */
public object CoreDocumentModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "core-document"
}
