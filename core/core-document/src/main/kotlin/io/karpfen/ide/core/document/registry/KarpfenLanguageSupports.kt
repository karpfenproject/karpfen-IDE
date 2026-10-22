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
 * KMeta: tokenizer, syntax analyzer, standalone semantics with the KMeta reader of dsl-tools. Wiring only, like every
 * support of [defaultLanguageRegistry].
 */
internal class KmetaLanguageSupport(reader: DslReader) : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KMETA
    override val tokenizer: Tokenizer = KmetaTokenizer()
    override val parser: DocumentParser =
        ComposedDocumentParser(language, KmetaSyntaxAnalyzer(), KmetaStandaloneSemantics(reader))
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
}

/**
 * KModel: tokenizer and syntax analyzer; no standalone semantics, because a model is only read against its metamodel,
 * which the project analysis of core-workspace supplies.
 */
internal class KmodelLanguageSupport : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL
    override val tokenizer: Tokenizer = KmodelTokenizer()
    override val parser: DocumentParser = ComposedDocumentParser(language, KmodelSyntaxAnalyzer(), semantics = null)
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
}

/** KStates: tokenizer with EVAL scanning, syntax analyzer, standalone semantics with the KStates reader of dsl-tools. */
internal class KstatesLanguageSupport(reader: DslReader) : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES
    override val tokenizer: Tokenizer = KstatesTokenizer()
    override val parser: DocumentParser =
        ComposedDocumentParser(language, KstatesSyntaxAnalyzer(), KstatesStandaloneSemantics(reader))
    override val lineComment: String = KarpfenSyntax.LINE_COMMENT
}

/**
 * Environment descriptors: the JSON tokenizer and the environment syntax analyzer, both written in this project rather
 * than taken from a JSON library, because the editor needs every token and value with its range, also in broken text.
 * The tokenizer is guarded like the ANTLR-based ones; there are no standalone semantics, because what a descriptor
 * names is checked by the workspace against the files of the folder. JSON has no comments, so there is no line comment.
 */
internal class EnvironmentLanguageSupport : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.ENVIRONMENT
    override val tokenizer: Tokenizer = FailSafeTokenizer(language, JsonTokenizer())
    override val parser: DocumentParser = ComposedDocumentParser(language, EnvironmentSyntaxAnalyzer(), semantics = null)
    override val lineComment: String? = null
}
