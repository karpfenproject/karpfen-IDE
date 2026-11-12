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
package io.karpfen.ide.core.document.registry

import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.language.LanguageRegistry

/**
 * The registry of the built-in karpfen languages: KMeta, KModel, KStates and environment descriptors. Building it only
 * wires objects and never tokenizes or parses. The standalone semantics of KMeta and KStates share [reader].
 *
 * The supports are the concrete factories of the [io.karpfen.ide.core.document.language.LanguageSupport] product
 * family. Each of the three modeling languages creates its tokenizer from the package `antlr.lex`. Its parser is
 * composed of the ANTLR syntax analyzer from `antlr.tree`. KMeta and KStates add the dsl-tools standalone semantics
 * from `dsl`. Environment descriptors combine the JSON tokenizer from `json` with the environment syntax analyzer from
 * `environment`. Diagram layouts use the JSON tokenizer alone. A further language needs a new support class in this package and one entry here. No existing
 * language class changes.
 */
public fun defaultLanguageRegistry(reader: DslReader = DslReader()): LanguageRegistry =
    LanguageRegistry(
        listOf(
            KmetaLanguageSupport(reader),
            KmodelLanguageSupport(),
            KstatesLanguageSupport(reader),
            EnvironmentLanguageSupport(),
            VisLanguageSupport(),
        ),
    )
