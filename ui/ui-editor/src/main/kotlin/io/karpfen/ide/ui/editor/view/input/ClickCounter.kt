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
package io.karpfen.ide.ui.editor.view.input

import kotlin.math.abs

/**
 * Counts consecutive clicks (1, 2, 3, …) from press times and positions: a press within [timeoutMillis] of the previous
 * one and within [slop] pixels of it continues the series, anything else starts a new one. Counted from the pointer
 * events themselves (their uptime), so synthesized test events and every platform behave alike. Used from the UI
 * thread.
 */
internal class ClickCounter(private val timeoutMillis: Long, private val slop: Float) {

    private var lastTime = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f
    private var count = 0

    /** Registers a press at [timeMillis] at ([x], [y]); returns its click count. */
    fun press(timeMillis: Long, x: Float, y: Float): Int {
        val continues = lastTime != Long.MIN_VALUE && timeMillis - lastTime <= timeoutMillis &&
            abs(x - lastX) <= slop && abs(y - lastY) <= slop
        count = if (continues) count + 1 else 1
        lastTime = timeMillis
        lastX = x
        lastY = y
        return count
    }
}
