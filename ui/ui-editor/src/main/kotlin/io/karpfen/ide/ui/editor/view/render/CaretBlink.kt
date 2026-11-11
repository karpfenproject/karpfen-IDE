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
package io.karpfen.ide.ui.editor.view.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/** The length of one blink phase of the caret, in milliseconds. */
internal const val CARET_BLINK_MILLIS: Long = 530

/**
 * Whether the caret is drawn now. The caret blinks, but stays solid while the user types. While [focused] is true, it
 * toggles every [CARET_BLINK_MILLIS]. Without focus the state stays true. Every change of [activity], such as the
 * carets or the text version, starts the cycle again in the visible phase. So the caret stays solid while the user
 * types or moves it. Only the draw phase reads the state.
 */
@Composable
internal fun rememberCaretVisible(focused: Boolean, activity: Any?): State<Boolean> {
    val visible = remember { mutableStateOf(true) }
    LaunchedEffect(focused, activity) {
        visible.value = true
        if (!focused) return@LaunchedEffect
        while (true) {
            delay(CARET_BLINK_MILLIS)
            visible.value = !visible.value
        }
    }
    return visible
}
