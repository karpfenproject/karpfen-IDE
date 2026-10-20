package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextSnapshot

/**
 * Parses a document into a [ParseOutcome] (spec §3.3). One implementation per language, reached through
 * [io.karpfen.ide.core.document.language.LanguageSupport.parser].
 *
 * Contract (MUST):
 * - Never throws, for any input. ANTLR's default recovery yields a partial tree for broken text; outline, index,
 *   folding and spans are extracted from whatever was recognized.
 * - The outcome's version equals [TextSnapshot.version].
 * - Stateless and thread-safe.
 * - Budget: a 5,000-line KStates document parses in < 150 ms (spec §3.4).
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
     * Convert KMeta and KStates files with dsl-tools when there are no syntax diagnostics (spec §3.3). Opt-in: no
     * product path uses it (decision 28 of the work-package plan); documents edited on their own and the tests of the
     * standalone semantics do. Not for `EVENTS.kmeta`: event payloads may reference domain types and only convert with
     * the domain metamodel as base types, which core-workspace does.
     */
    STANDALONE,

    /** Syntax only; never call dsl-tools converters. */
    SYNTAX_ONLY,
}
