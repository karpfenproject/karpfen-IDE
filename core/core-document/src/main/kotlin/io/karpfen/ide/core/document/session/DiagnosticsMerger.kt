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
package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextSnapshot

/**
 * Merges the diagnostic sources of one document into the [VersionedDiagnostics] of the newest snapshot of [text]. The
 * ranges of every source are mapped from the version they were computed for with `EditableText.mapRange`, the one
 * range-mapping rule of the IDE. A diagnostic whose range does not map, because that part of the history was evicted,
 * is dropped. [mergeDiagnostics], the one merge rule, then combines the rest. The merger owns neither a mapping rule
 * nor a merge rule.
 *
 * The external sets may be reported once and then live for many edits. They are mapped from their [anchors], which
 * hold the snapshot of the previous merge, so they never expire with the edit history.
 *
 * It reads `text.snapshot` and updates the [anchors]. It is thread-safe, because range mapping and the anchors are.
 */
internal class DiagnosticsMerger(
    private val text: EditableText,
    private val anchors: ExternalDiagnosticsAnchors = ExternalDiagnosticsAnchors(),
) {

    /**
     * The merged diagnostics of [tokens], [parse] and the [external] sets by source. They are in the coordinates of the
     * newest snapshot of [text] at the time of the call. That snapshot is never older than any source, since all
     * sources come from versions of [text].
     */
    fun merge(tokens: TokenizeResult, parse: ParseOutcome?, external: Map<String, ExternalDiagnosticSet>): VersionedDiagnostics {
        val target = text.snapshot.value
        val lexical = mapToTarget(tokens.diagnostics, tokens.version, text.lineage, target)
        val mappedParse = parse?.let { outcome ->
            outcome.copy(
                syntaxDiagnostics = mapToTarget(outcome.syntaxDiagnostics, outcome.version, text.lineage, target),
                standaloneDiagnostics = mapToTarget(outcome.standaloneDiagnostics, outcome.version, text.lineage, target),
            )
        }
        val mappedExternal = anchors.mapAll(external, target) { set ->
            mapToTarget(set.diagnostics, set.snapshot.version, set.snapshot.lineage, target)
        }
        return VersionedDiagnostics(target, mergeDiagnostics(lexical, mappedParse, mappedExternal))
    }

    /**
     * The [diagnostics] of the given version and lineage, mapped to [target]. Those whose range does not map are
     * dropped.
     */
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
