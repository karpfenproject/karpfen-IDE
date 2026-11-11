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

/**
 * Minimal scrolling to reveal an item. It is pure and shared by [TreeView] and [TabStrip]. It finds the smallest change
 * of the scroll position that makes the item fully visible, which aligns the item with the nearer viewport edge. An
 * item larger than the viewport is aligned with the viewport's start.
 */
internal object ScrollReveal {

    /**
     * The new scroll position that reveals the item from [itemStart] to [itemEnd], end exclusive, in a viewport of
     * [viewportSize] that currently starts at [viewportStart]. A scroll position is the content offset at the
     * viewport's start. Returns null if the item is fully visible already.
     */
    fun target(itemStart: Int, itemEnd: Int, viewportStart: Int, viewportSize: Int): Int? {
        val viewportEnd = viewportStart + viewportSize
        return when {
            itemStart < viewportStart -> itemStart
            itemEnd > viewportEnd -> if (itemEnd - itemStart >= viewportSize) itemStart else itemEnd - viewportSize
            else -> null
        }
    }
}
