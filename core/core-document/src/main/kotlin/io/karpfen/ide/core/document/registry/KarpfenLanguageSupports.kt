package io.karpfen.ide.core.document.registry

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.lex.KmetaTokenizer
import io.karpfen.ide.core.document.antlr.lex.KmodelTokenizer
import io.karpfen.ide.core.document.antlr.lex.KstatesTokenizer
import io.karpfen.ide.core.document.antlr.tree.KmetaSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KmodelSyntaxAnalyzer
import io.karpfen.ide.core.document.antlr.tree.KstatesSyntaxAnalyzer
import io.karpfen.ide.core.document.dsl.DslConverter
import io.karpfen.ide.core.document.dsl.KmetaStandaloneSemantics
import io.karpfen.ide.core.document.dsl.KstatesStandaloneSemantics
import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ComposedDocumentParser
import io.karpfen.ide.core.document.parse.DocumentParser
import io.karpfen.ide.core.document.syntax.Tokenizer

// The three concrete factories of the LanguageSupport product family (Abstract Factory, spec §1a). Wiring only: each
// bundle instantiates its language's tokenizer (WP-TOKENS, package antlr.lex) and composes its parser from the ANTLR
// syntax analyzer (WP-PARSE, antlr.tree) and the dsl-tools standalone semantics (WP-PARSE, dsl). A fourth language is
// a new class here plus one entry in defaultLanguageRegistry(); no existing language class changes.

/** KMeta: tokenizer, syntax analyzer, standalone conversion with `KmetaDSLConverter`. */
internal class KmetaLanguageSupport(converter: DslConverter) : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KMETA
    override val tokenizer: Tokenizer = KmetaTokenizer()
    override val parser: DocumentParser =
        ComposedDocumentParser(language, KmetaSyntaxAnalyzer(), KmetaStandaloneSemantics(converter))
}

/** KModel: tokenizer and syntax analyzer; no standalone semantics (a model needs its metamodel, spec §3.3). */
internal class KmodelLanguageSupport : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KMODEL
    override val tokenizer: Tokenizer = KmodelTokenizer()
    override val parser: DocumentParser = ComposedDocumentParser(language, KmodelSyntaxAnalyzer(), semantics = null)
}

/** KStates: tokenizer with EVAL scanning, syntax analyzer, standalone conversion with `KstatesDSLConverter`. */
internal class KstatesLanguageSupport(converter: DslConverter) : LanguageSupport {
    override val language: KarpfenLanguage = KarpfenLanguage.KSTATES
    override val tokenizer: Tokenizer = KstatesTokenizer()
    override val parser: DocumentParser =
        ComposedDocumentParser(language, KstatesSyntaxAnalyzer(), KstatesStandaloneSemantics(converter))
}
