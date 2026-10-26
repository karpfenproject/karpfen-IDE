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
 * The registry of the built-in karpfen languages (KMeta, KModel, KStates and environment descriptors). Construction
 * only wires objects; it never tokenizes or parses. [reader] is shared by the standalone semantics of KMeta and
 * KStates.
 *
 * The supports are the concrete factories of the [io.karpfen.ide.core.document.language.LanguageSupport] product
 * family. The three modeling languages instantiate their tokenizer (package `antlr.lex`) and compose their parser from
 * the ANTLR syntax analyzer (`antlr.tree`) and the dsl-tools standalone semantics (`dsl`); environment descriptors
 * combine the JSON tokenizer (`json`) with the environment syntax analyzer (`environment`). A further language is a
 * new support class in this package plus one entry here; no existing language class changes.
 */
public fun defaultLanguageRegistry(reader: DslReader = DslReader()): LanguageRegistry =
    LanguageRegistry(
        listOf(
            KmetaLanguageSupport(reader),
            KmodelLanguageSupport(),
            KstatesLanguageSupport(reader),
            EnvironmentLanguageSupport(),
        ),
    )
