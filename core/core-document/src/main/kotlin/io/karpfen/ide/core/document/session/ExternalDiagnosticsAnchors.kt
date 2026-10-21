package io.karpfen.ide.core.document.session

import io.karpfen.ide.core.document.Diagnostic
import io.karpfen.ide.core.text.TextSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * Re-anchors the external diagnostic sets of one document as its text advances (spec §3.4, decision 28): each set is
 * mapped from where the previous merge left it, not from the snapshot it was reported for. A set reported once — the
 * FILE findings live until the next save or reload — therefore never falls out of the text's retained edit history
 * (`EditableText.mapRange` answers null for evicted versions), and each merge maps only the versions since the
 * previous one.
 *
 * Thread-safe: the anchors are an immutable map replaced atomically. An anchor newer than a merge's target is not used
 * (that set is mapped from its own snapshot), so a merge never maps backwards.
 */
internal class ExternalDiagnosticsAnchors {

    /** The set [reported] by a source and its diagnostics mapped to a later snapshot ([anchored]). */
    private class Anchor(val reported: ExternalDiagnosticSet, val anchored: ExternalDiagnosticSet)

    private val anchors = AtomicReference<Map<String, Anchor>>(emptyMap())

    /**
     * Maps every set of [sets] (by source) to [target] with [mapToTarget], starting from the set's anchor if it has one
     * for exactly this reported set, and keeps the results as the new anchors (sources that are no longer reported are
     * forgotten). Returns the mapped diagnostics in the iteration order of [sets].
     */
    fun mapAll(
        sets: Map<String, ExternalDiagnosticSet>,
        target: TextSnapshot,
        mapToTarget: (ExternalDiagnosticSet) -> List<Diagnostic>,
    ): List<List<Diagnostic>> {
        val previous = anchors.get()
        val next = HashMap<String, Anchor>(sets.size)
        val mapped = sets.map { (source, set) ->
            val anchor = previous[source]?.takeIf { it.reported === set && it.anchored.snapshot.version <= target.version }
            mapToTarget(anchor?.anchored ?: set).also { diagnostics ->
                next[source] = Anchor(set, ExternalDiagnosticSet(target, diagnostics))
            }
        }
        anchors.set(next)
        return mapped
    }
}
