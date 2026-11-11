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
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory

/**
 * The [diagnostics] that an external source reported for [snapshot]. The set is immutable.
 *
 * @property snapshot the text the ranges of [diagnostics] refer to.
 */
internal data class ExternalDiagnosticSet(val snapshot: TextSnapshot, val diagnostics: List<Diagnostic>)

/**
 * The external diagnostic sets of one document by source, as [DocumentSession.setExternalDiagnostics] reports them.
 * The store is thread-safe. The sets are an immutable map in a [MutableStateFlow] that is replaced atomically. So any
 * thread may [set] while the session's merger observes [sets].
 *
 * @param lineage the lineage of the document's text. Sets computed for a snapshot of another lineage are ignored,
 *   because their ranges cannot be mapped into this text.
 */
internal class ExternalDiagnosticsStore(private val lineage: TextLineage) {

    private val state = MutableStateFlow<Map<String, ExternalDiagnosticSet>>(emptyMap())

    /** The current sets by source. */
    val sets: StateFlow<Map<String, ExternalDiagnosticSet>> = state.asStateFlow()

    /**
     * Replaces the set of [source] by [diagnostics] computed for [snapshot]. The store keeps a copy of the list. An
     * empty list removes the set. Returns false and changes nothing if [snapshot] belongs to another lineage.
     */
    fun set(source: String, snapshot: TextSnapshot, diagnostics: List<Diagnostic>): Boolean {
        if (snapshot.lineage !== lineage) {
            logger.debug("Ignoring {} diagnostics of source '{}' for a snapshot of another text", diagnostics.size, source)
            return false
        }
        val copy = diagnostics.toList()
        state.update { sets -> if (copy.isEmpty()) sets - source else sets + (source to ExternalDiagnosticSet(snapshot, copy)) }
        return true
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ExternalDiagnosticsStore::class.java)
    }
}
