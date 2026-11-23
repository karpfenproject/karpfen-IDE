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
package io.karpfen.ide.ui.diagram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.notation.diagram.layout.DiagramLayout
import io.karpfen.ide.notation.sync.DiagramState
import io.karpfen.ide.notation.sync.DiagramTracker
import io.karpfen.ide.notation.sync.VisFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * What the diagram panes of one window share about a diagram. Two panes that show the diagram of the same file, such
 * as the two halves of a split editor, show one diagram: the one derived from the text and the one layout by hand.
 *
 * The share of a diagram is the [DiagramTracker] that derives it from the text of the document and the
 * [DiagramEditing] that holds its layout by hand and writes it to the companion file. Both belong to the file and not
 * to a view of it. A box dragged in one pane therefore moves in every pane while it is dragged, every pane follows a
 * change of the text with the same diagram, and a change is written to the companion file once, by the one editing,
 * and never by two editings that disagree. What belongs to one view stays with the pane: its zoom and position, an
 * open context menu, a code cell, and the live marks of a run.
 *
 * A pane leases the share of its diagram with [lease] inside `remember`. The lease is a `RememberObserver`, so the
 * share lives while at least one pane in the composition holds a lease of it and is dropped with the last one. Its
 * derivation runs in [scope] while it is held. The writes to the companion file run in [scope] as well, so a write
 * that a pane started is finished when that pane goes away while another pane still shows the diagram.
 *
 * The object belongs to the UI thread. [rememberSharedDiagrams] makes one for a part of the composition, such as the
 * editor area of a window. A `DiagramPane` without one makes its own, which shares nothing.
 */
public class SharedDiagrams internal constructor(private val scope: CoroutineScope) {

    private val shares = HashMap<ShareKey, DiagramShare<*, *>>()

    /**
     * The share of the diagram of the kind [notation] of [modelFile], whose text [session] holds. A share that the
     * composition holds already is given out again, and otherwise a new one is made. The share counts as held once the
     * lease is remembered.
     */
    internal fun <D : Any, L : DiagramLayout> lease(notation: DiagramNotation<D, L>, session: DocumentSession, modelFile: Path): DiagramLease<D, L> {
        val key = ShareKey(session, VisFiles.pathFor(modelFile), notation)
        @Suppress("UNCHECKED_CAST")
        val share = shares.getOrPut(key) { DiagramShare(notation, session, key.visFile, scope) } as DiagramShare<D, L>
        return DiagramLease(this, key, share)
    }

    /** The number of shares that are held, for tests. */
    internal val size: Int get() = shares.size

    /** Counts one more holder of [share] and starts it with its first holder. */
    internal fun join(key: ShareKey, share: DiagramShare<*, *>) {
        if (shares[key] == null) shares[key] = share
        share.holders++
        if (share.holders == 1) share.start()
    }

    /** Counts one holder of [share] less, and stops and drops it with its last holder. */
    internal fun leave(key: ShareKey, share: DiagramShare<*, *>, held: Boolean) {
        if (held) share.holders--
        if (share.holders > 0) return
        share.stop()
        if (shares[key] === share) shares.remove(key)
    }

    /** The identity of a share: the document, the companion file and the kind of diagram. */
    internal data class ShareKey(val session: DocumentSession, val visFile: Path, val notation: DiagramNotation<*, *>)
}

/**
 * Makes [SharedDiagrams] for the composition below the caller, with the coroutine scope of the caller's place in the
 * composition for the derivations and the writes.
 */
@Composable
public fun rememberSharedDiagrams(): SharedDiagrams {
    val scope = rememberCoroutineScope()
    return remember(scope) { SharedDiagrams(scope) }
}

/**
 * The diagram of the kind [notation] of one file, shared by the panes that show it. [state] is the diagram derived
 * from the text of [session], and [editing] holds its layout by hand and writes it to [visFile].
 *
 * @property holders how many remembered leases hold the share.
 */
internal class DiagramShare<D : Any, L : DiagramLayout>(
    private val notation: DiagramNotation<D, L>,
    private val session: DocumentSession,
    private val visFile: Path,
    private val scope: CoroutineScope,
) {
    private val derived = MutableStateFlow<DiagramState<D>>(DiagramState.EMPTY)
    private val tracker = DiagramTracker(notation.projection)
    private var derivation: Job? = null

    var holders: Int = 0

    /** What the panes call after the layout was written. The pane that showed the diagram last sets it. */
    var onSaved: () -> Unit = {}

    /** The diagram as it is derived from the text. */
    val state: StateFlow<DiagramState<D>> get() = derived.asStateFlow()

    /** The layout by hand of the diagram. Every write of it goes to the companion file once. */
    val editing: DiagramEditing<D, L> = DiagramEditing(notation.arrangement) { layout ->
        scope.launch {
            withContext(Dispatchers.IO) { VisFiles.write(visFile, layout) }
            onSaved()
        }
    }

    /** Starts deriving the diagram from the text, unless it runs already. */
    fun start() {
        if (derivation?.isActive == true) return
        derivation = scope.launch {
            combine(session.text.snapshot, session.parse) { snapshot, outcome -> tracker.update(snapshot, outcome) }.collect { derived.value = it }
        }
    }

    /** Stops deriving the diagram. A write to the companion file that is on its way is finished. */
    fun stop() {
        derivation?.cancel()
        derivation = null
    }
}

/**
 * A pane's hold on a [share]. It is a `RememberObserver`: the share counts the lease as a holder from the moment the
 * lease is remembered until it is forgotten. A lease that the composition abandons was never counted and only lets
 * the share go if nobody else holds it.
 */
internal class DiagramLease<D : Any, L : DiagramLayout>(
    private val owner: SharedDiagrams,
    private val key: SharedDiagrams.ShareKey,
    val share: DiagramShare<D, L>,
) : RememberObserver {

    override fun onRemembered() {
        owner.join(key, share)
    }

    override fun onForgotten() {
        owner.leave(key, share, held = true)
    }

    override fun onAbandoned() {
        owner.leave(key, share, held = false)
    }
}
