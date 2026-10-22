package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses a document into a [ParseOutcome]. One implementation per language, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.parser].
 *
 * Contract (MUST):
 * - Never throws, for any input. ANTLR's default recovery yields a partial tree for broken text; outline, index,
 *   folding and spans are extracted from whatever was recognized.
 * - The outcome's version equals [TextSnapshot.version].
 * - Stateless and thread-safe.
 * - Budget: a 5,000-line KStates document parses in < 150 ms. This is a target that a time-budget test documents; no
 *   test run executes it.
 */
public interface DocumentParser {
    /**
     * Parses [snapshot]; [mode] decides whether the standalone dsl-tools conversion may run ([SemanticMode.SYNTAX_ONLY]
     * by default, like `DocumentSessionConfig`; standalone conversion is opt-in).
     */
    public fun parse(snapshot: TextSnapshot, mode: SemanticMode = SemanticMode.SYNTAX_ONLY): ParseOutcome
}

/** Whether [DocumentParser.parse] runs the standalone dsl-tools conversion. */
public enum class SemanticMode {
    /**
     * Convert KMeta and KStates files with dsl-tools when there are no syntax diagnostics. Opt-in: no product path uses
     * it, because in a workspace the project analysis is the only place that converts files, so each file is converted
     * once and opening a file never changes what is reported. Documents edited on their own and the tests of the
     * standalone semantics use it. Not for `EVENTS.kmeta`: event payloads may reference domain types and only convert
     * with the domain metamodel as base types, which core-workspace does.
     */
    STANDALONE,

    /** Syntax only; never call dsl-tools converters. */
    SYNTAX_ONLY,
}
