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
import io.karpfen.ide.core.text.TextSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * Re-anchors the external diagnostic sets of one document as its text advances. Each set is mapped from where the
 * previous merge left it, not from the snapshot it was reported for. A set may be reported once and live long. The
 * FILE findings, for example, live until the next save or reload. Such a set never falls out of the retained edit
 * history of the text, where `EditableText.mapRange` answers null for evicted versions. Each merge also maps only the
 * versions since the previous one.
 *
 * Mapping step by step differs from mapping from the reported version only for a range that collapsed to an empty
 * one. Such a range then stays behind text typed at it, like any empty range.
 *
 * The class is thread-safe, because the anchors are an immutable map replaced atomically. An anchor newer than the
 * target of a merge is not used, and that set is mapped from its own snapshot. So a merge never maps backwards.
 */
internal class ExternalDiagnosticsAnchors {

    /** The set [reported] by a source, and as [anchored] its diagnostics mapped to a later snapshot. */
    private class Anchor(val reported: ExternalDiagnosticSet, val anchored: ExternalDiagnosticSet)

    /** The anchors by source, replaced as a whole by every [mapAll]. */
    private val anchors = AtomicReference<Map<String, Anchor>>(emptyMap())

    /**
     * Maps every set of [sets] to [target] with [mapToTarget]. A set starts from its anchor if the anchor belongs to
     * exactly this reported set. The results become the new anchors, and sources that are no longer reported are
     * forgotten. Returns the mapped diagnostics in the iteration order of [sets].
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
