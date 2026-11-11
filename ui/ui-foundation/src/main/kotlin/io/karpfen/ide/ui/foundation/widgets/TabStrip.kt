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
package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * One tab of a [TabStrip]. [id] is stable across recompositions, for example the document path.
 *
 * @property title the tab's label.
 * @property tooltip shown while the pointer rests on the tab, or null for none.
 * @property isDirty whether the tab shows the dot of unsaved changes.
 * @property icon drawn untinted before the title, or null for none.
 */
@Immutable
public data class TabItem(
    public val id: String,
    public val title: String,
    public val tooltip: String? = null,
    public val isDirty: Boolean = false,
    public val icon: ImageVector? = null,
)

/** Test tags of [TabStrip], for headless UI tests of the foundation and of the modules that use it. */
public object TabStripTestTags {
    /** The scrollable strip. */
    public const val STRIP: String = "TabStrip"

    /** The tab [id]. */
    public fun tab(id: String): String = "TabStrip.tab:$id"

    /** The close button of the tab [id]. */
    public fun close(id: String): String = "TabStrip.close:$id"

    /** The dirty dot of the tab [id]. It exists only while the tab is dirty. */
    public fun dirty(id: String): String = "TabStrip.dirty:$id"
}

/**
 * A horizontal strip of tabs. Each tab shows its title, its tooltip, a dot for unsaved changes and a close button. The
 * strip is stateless.
 *
 * A primary click on a tab selects it through [onSelect]. Its close button or a middle click closes it through
 * [onClose] without selecting it. When the tabs overflow, the strip scrolls with the horizontal wheel or the trackpad
 * and also with the vertical wheel. Whenever [selectedId] changes, the strip scrolls minimally so that the tab is fully
 * visible. The strip needs `KarpfenTheme`.
 */
@Composable
public fun TabStrip(
    tabs: List<TabItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KarpfenTheme.colors
    val scroll = rememberScrollState()
    val extents = remember { mutableStateMapOf<String, TabExtent>() }
    RevealSelectedTabEffect(selectedId, extents, scroll)

    Box(modifier.fillMaxWidth().height(TabStripDefaults.Height).background(colors.panel)) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            Modifier
                .fillMaxHeight()
                .testTag(TabStripTestTags.STRIP)
                .verticalWheelScroll(scroll)
                .horizontalScroll(scroll),
        ) {
            tabs.forEach { tab ->
                key(tab.id) {
                    DisposableEffect(tab.id) { onDispose { extents.remove(tab.id) } }
                    TabView(
                        tab = tab,
                        isSelected = tab.id == selectedId,
                        onSelect = onSelect,
                        onClose = onClose,
                        modifier = Modifier.onPlaced { coordinates ->
                            val start = coordinates.positionInParent().x.roundToInt()
                            extents[tab.id] = TabExtent(start, start + coordinates.size.width)
                        },
                    )
                }
            }
        }
    }
}

/** The horizontal extent of a tab inside the scrolled row, in px, with the end exclusive. */
internal data class TabExtent(val start: Int, val end: Int)

/** Fixed measures of [TabStrip]. */
internal object TabStripDefaults {
    /** Height of the strip. */
    val Height = 30.dp
}

/**
 * Lets the vertical mouse wheel scroll the strip too. Wheel down moves towards the last tab, with the direction
 * reversed as in the scrollable of `verticalScroll`. Mouse drags never scroll on desktop, so this adds no drag gesture.
 */
private fun Modifier.verticalWheelScroll(scroll: ScrollState): Modifier =
    scrollable(scroll, Orientation.Vertical, reverseDirection = true)

/**
 * Scrolls minimally so the tab [selectedId] is visible whenever [selectedId] changes. It waits until the tab is placed
 * and the strip is laid out.
 */
@Composable
private fun RevealSelectedTabEffect(selectedId: String?, extents: SnapshotStateMap<String, TabExtent>, scroll: ScrollState) {
    LaunchedEffect(selectedId) {
        if (selectedId == null) return@LaunchedEffect
        val extent = snapshotFlow { extents[selectedId] }.filterNotNull().first()
        val viewport = snapshotFlow { scroll.viewportSize }.first { it > 0 }
        ScrollReveal.target(extent.start, extent.end, scroll.value, viewport)?.let { scroll.scrollTo(it) }
    }
}
