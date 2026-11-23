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
package io.karpfen.ide.ui.foundation.layout

/**
 * The size rules of a [ShareSplitPane] along its main axis, in px. They are pure and unit-tested without Compose. The
 * first part takes the share of the space next to the divider of [divider] px, and the second part takes the rest.
 * Every share is kept between [minShare] and [maxShare], so neither part vanishes.
 *
 * @property minShare the smallest share of the first part.
 * @property maxShare the greatest share of the first part.
 * @property divider the space the divider line takes between the parts.
 */
internal data class ShareSplitGeometry(val minShare: Float, val maxShare: Float, val divider: Float) {

    init {
        require(minShare in 0f..maxShare && maxShare <= 1f) { "The shares must satisfy 0 <= min <= max <= 1: $minShare, $maxShare" }
    }

    /** The space both parts share in a container of [container] px, which is all of it but the divider. */
    fun available(container: Float): Float = (container - divider).coerceAtLeast(0f)

    /** [share] kept between [minShare] and [maxShare]. */
    fun bounded(share: Float): Float = share.coerceIn(minShare, maxShare)

    /** The sizes of both parts for [share] in a container of [container] px. */
    fun layout(container: Float, share: Float): SplitSizes {
        val available = available(container)
        val first = available * bounded(share)
        return SplitSizes(first, available - first)
    }

    /**
     * The share of the first part after the divider moved by [delta] px from where it was when the first part had
     * [start] px, in a container of [container] px. A positive [delta] moves the divider right or down. A container
     * without space for the parts keeps [fallback].
     */
    fun dragged(container: Float, start: Float, delta: Float, fallback: Float): Float {
        val available = available(container)
        if (available <= 0f) return bounded(fallback)
        return bounded((start + delta) / available)
    }
}

/**
 * Tracks one divider drag of a [ShareSplitPane]. It is helper state on the Compose side and is used on the UI thread
 * only. It remembers the last layout and adds up the pointer movement since the press, so the divider follows the
 * pointer exactly and snaps back when the pointer returns from beyond a bound.
 */
internal class ShareDragTracker {
    private var container = 0f
    private var laidOutFirst = 0f
    private var laidOutShare = 0f
    private var start = 0f
    private var total = 0f
    private var lastReported = 0f

    /** Records the latest layout: the container's and the first part's main-axis sizes in px and the share shown. */
    fun onLaidOut(containerPx: Float, firstPx: Float, share: Float) {
        container = containerPx
        laidOutFirst = firstPx
        laidOutShare = share
    }

    /** Starts a drag at the current layout. */
    fun start() {
        start = laidOutFirst
        total = 0f
        lastReported = laidOutShare
    }

    /** Adds [delta] px of pointer movement and returns the new share, or null if the share did not change. */
    fun drag(delta: Float, geometry: ShareSplitGeometry): Float? {
        total += delta
        val target = geometry.dragged(container, start, total, lastReported)
        if (target == lastReported) return null
        lastReported = target
        return target
    }
}
