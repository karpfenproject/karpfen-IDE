package io.karpfen.ide.core.document.antlr.tree

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.antlr.CodePointOffsets
import io.karpfen.ide.core.document.antlr.IdeErrorListener
import io.karpfen.ide.core.document.antlr.KarpfenLexers
import io.karpfen.ide.core.text.TextSnapshot
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.TokenStream

/**
 * Runs a dsl-tools parser the IDE way (spec §3.3): a `CommonTokenStream` over [KarpfenLexers.create], one
 * [IdeErrorListener] attached to the lexer and the parser (no console output), ANTLR's default error recovery, so
 * broken text still yields a partial tree. The generated parser and its entry rule are the per-language strategy.
 *
 * Stateless and thread-safe (every call creates its own lexer, stream and parser; the generated parsers share only
 * ANTLR's thread-safe prediction caches).
 *
 * @param language selects the generated lexer.
 * @param newParser creates the generated parser over a token stream, e.g. `::KmetaParser`.
 * @param entryRule invokes the grammar's entry rule, e.g. `KmetaParser::kmeta_file`.
 */
internal class TreeParser<P : Parser, T : ParserRuleContext>(
    private val language: KarpfenLanguage,
    private val newParser: (TokenStream) -> P,
    private val entryRule: (P) -> T,
) {
    /** Parses [snapshot]; never throws for any input (errors become the tree's diagnostics). */
    fun parse(snapshot: TextSnapshot): ParsedTree<T> {
        val offsets = CodePointOffsets.of(snapshot.text)
        val listener = IdeErrorListener(offsets)
        val stream = CommonTokenStream(KarpfenLexers.create(language, CharStreams.fromString(snapshot.text), listener))
        val root = entryRule(listener.attachTo(newParser(stream)))
        // Recovery may stop before the end of the input; the walkers and the EVAL scanner need every token.
        stream.fill()
        return ParsedTree(snapshot, offsets, stream.tokens.toList(), root, listener.diagnostics)
    }
}
