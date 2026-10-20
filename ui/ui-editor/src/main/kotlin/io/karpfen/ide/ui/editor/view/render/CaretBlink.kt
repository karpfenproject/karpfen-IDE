package io.karpfen.ide.ui.editor.view.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/** Milliseconds per blink phase of the caret. */
internal const val CARET_BLINK_MILLIS: Long = 530

/**
 * Whether the caret is drawn now (spec §7.2: blinking caret, solid while typing): visible and blinking every
 * [CARET_BLINK_MILLIS] while [focused]; every change of [activity] (the carets, the text version) restarts the cycle in
 * the visible phase, so the caret stays solid while the user types or moves it. Only the draw phase reads the state.
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
