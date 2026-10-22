package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.KarpfenLanguage

/**
 * Everything derived from parsing one text version. All ranges are in the coordinates of [version].
 *
 * @property language the language the text was parsed as.
 * @property version the parsed text version.
 * @property syntaxDiagnostics errors reported while parsing: PARSER errors plus LEXER errors seen by the parser's own
 *   lexer, sorted with [Diagnostic.ORDER]. (The document session shows LEXER errors from the tokenizer and only the
 *   PARSER ones from here, so nothing is reported twice.)
 * @property outline top-level outline nodes in document order.
 * @property index declarations and references.
 * @property folding foldable regions sorted by start.
 * @property semanticSpans sorted, non-overlapping semantic spans.
 * @property standaloneModel the dsl-tools model of this file alone, if [SemanticMode.STANDALONE] was requested, the
 *   language supports it (KMeta, KStates) and there were no syntax diagnostics; null otherwise.
 * @property standaloneDiagnostics DSL_TOOLS diagnostics of the standalone conversion (empty if it did not run).
 */
public data class ParseOutcome(
    public val language: KarpfenLanguage,
    public val version: Long,
    public val syntaxDiagnostics: List<Diagnostic>,
    public val outline: List<OutlineNode>,
    public val index: SyntaxIndex,
    public val folding: List<FoldingRange>,
    public val semanticSpans: List<SemanticSpan>,
    public val standaloneModel: DslModel?,
    public val standaloneDiagnostics: List<Diagnostic>,
) {
    init {
        require(version >= 0) { "version must be >= 0, was $version" }
    }

    /** True if parsing reported no syntax diagnostics, which makes this outcome the session's new last good parse. */
    public val isSyntaxClean: Boolean get() = syntaxDiagnostics.isEmpty()
}
