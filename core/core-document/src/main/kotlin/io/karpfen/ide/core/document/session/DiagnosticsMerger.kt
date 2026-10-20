package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Merges the diagnostic sources of one document into [VersionedDiagnostics] of the newest snapshot of [text]
 * (spec §3.4): every source's ranges are mapped from the version they were computed for with `EditableText.mapRange`
 * (the one range-mapping rule, decision 35; diagnostics whose range does not map — evicted history — are dropped),
 * then combined by [mergeDiagnostics] (the one merge rule, decision 28). It owns neither a mapping nor a merge rule.
 *
 * Pure apart from reading `text.snapshot`; thread-safe, because mapping is (decision 35).
 */
internal class DiagnosticsMerger(private val text: EditableText) {

    /**
     * The merged diagnostics of [tokens], [parse] and [external], in the coordinates of the newest snapshot of [text]
     * at the time of the call (never older than any source, since all of them come from versions of [text]).
     */
    fun merge(tokens: TokenizeResult, parse: ParseOutcome?, external: Collection<ExternalDiagnosticSet>): VersionedDiagnostics {
        val target = text.snapshot.value
        val lexical = mapToTarget(tokens.diagnostics, tokens.version, text.lineage, target)
        val mappedParse = parse?.let { outcome ->
            outcome.copy(
                syntaxDiagnostics = mapToTarget(outcome.syntaxDiagnostics, outcome.version, text.lineage, target),
                standaloneDiagnostics = mapToTarget(outcome.standaloneDiagnostics, outcome.version, text.lineage, target),
            )
        }
        val mappedExternal = external.map { set ->
            mapToTarget(set.diagnostics, set.snapshot.version, set.snapshot.lineage, target)
        }
        return VersionedDiagnostics(target, mergeDiagnostics(lexical, mappedParse, mappedExternal))
    }

    private fun mapToTarget(
        diagnostics: List<Diagnostic>,
        fromVersion: Long,
        fromLineage: TextLineage,
        target: TextSnapshot,
    ): List<Diagnostic> = diagnostics.mapNotNull { diagnostic ->
        text.mapRange(diagnostic.range, fromVersion, fromLineage, target.version)?.let { range ->
            if (range == diagnostic.range) diagnostic else diagnostic.copy(range = range)
        }
    }
}
