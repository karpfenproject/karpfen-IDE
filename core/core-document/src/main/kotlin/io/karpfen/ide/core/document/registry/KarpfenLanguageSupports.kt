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

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.lex.FailSafeTokenizer
import io.karpfen.ide.core.document.antlr.lex.KmetaTokenizer
import io.karpfen.ide.core.document.antlr.lex.KmodelTokenizer
import io.karpfen.ide.core.document.antlr.lex.KstatesTokenizer
import io.karpfen.ide.core.document.antlr.tree.KmetaSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KmodelSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.KmetaStandaloneSemantics
import io.karpfen.ide.core.document.dsl.KstatesStandaloneSemantics
import io.karpfen.ide.core.document.environment.EnvironmentSyntaxAnalyzer
import io.karpfen.ide.core.document.json.JsonTokenizer
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ComposedDocumentParser
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.document.syntax.Tokenizer

/**
 * The KMeta support. It wires the tokenizer, the syntax analyzer and the standalone semantics with the KMeta reader of
 * dsl-tools. Like every support of [defaultLanguageRegistry], it only wires objects.
 */
internal class KmetaLanguageSupport(reader: DslReader) : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KMETA
    override val tokenizer: Tokenizer = KmetaTokenizer()
    override val parser: DocumentParser =
        ComposedDocumentParser(language, KmetaSyntaxAnalyzer(), KmetaStandaloneSemantics(reader))
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
}

/**
 * The KModel support, with a tokenizer and a syntax analyzer. It has no standalone semantics, because a model is only
 * read against its metamodel. The project analysis of core-workspace supplies that metamodel.
 */
internal class KmodelLanguageSupport : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL
    override val tokenizer: Tokenizer = KmodelTokenizer()
    override val parser: DocumentParser = ComposedDocumentParser(language, KmodelSyntaxAnalyzer(), semantics = null)
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
}

/**
 * The KStates support. It wires the tokenizer with EVAL scanning, the syntax analyzer and the standalone semantics
 * with the KStates reader of dsl-tools.
 */
internal class KstatesLanguageSupport(reader: DslReader) : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES
    override val tokenizer: Tokenizer = KstatesTokenizer()
    override val parser: DocumentParser =
        ComposedDocumentParser(language, KstatesSyntaxAnalyzer(), KstatesStandaloneSemantics(reader))
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
}

/**
 * The support for environment descriptors. It wires the JSON tokenizer and the environment syntax analyzer. Both are
 * written in this project and not taken from a JSON library, because the editor needs every token and value with its
 * range, even in broken text. The tokenizer is guarded like the ANTLR-based ones. There are no standalone semantics,
 * because the workspace checks what a descriptor names against the files of the folder. JSON has no comments, so
 * there is no line comment.
 */
internal class EnvironmentLanguageSupport : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.ENVIRONMENT
    override val tokenizer: Tokenizer = FailSafeTokenizer(language, JsonTokenizer())
    override val parser: DocumentParser = ComposedDocumentParser(language, EnvironmentSyntaxAnalyzer(), semantics = null)
    override val lineComment: String? = null
}
