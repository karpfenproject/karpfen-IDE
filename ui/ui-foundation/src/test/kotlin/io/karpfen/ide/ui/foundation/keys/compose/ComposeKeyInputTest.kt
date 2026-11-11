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
package io.karpfen.ide.ui.foundation.keys.compose

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The key adapter inside a composed, focused component, run headless with `runComposeUiTest`. The tests use key input
 * that the test framework injects, which follows a US layout, and synthesized events of other layouts sent to the
 * focused node. Synthesizing a key event with a code point needs Compose's `KeyEvent(…)` factory, which is marked
 * internal and unstable. The test framework uses it the same way, and production code never constructs key events.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ComposeKeyInputTest {

    private val target = "target"

    /** Composes a focused box that records the strokes of every key event it receives. */
    private fun ComposeUiTest.recordStrokes(): MutableList<KeyStroke> {
        val strokes = mutableListOf<KeyStroke>()
        setContent {
            val focus = remember { FocusRequester() }
            Box(
                Modifier.size(50.dp).testTag(target)
                    .onKeyEvent { event -> event.toKeyStroke()?.let { strokes += it } != null }
                    .focusRequester(focus)
                    .focusable(),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        waitForIdle()
        return strokes
    }

    @Test
    fun `typed shortcuts arrive as strokes with their modifiers`() = runComposeUiTest {
        val strokes = recordStrokes()

        onNodeWithTag(target).performKeyInput {
            withKeyDown(Key.MetaLeft) { pressKey(Key.S) }
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
            pressKey(Key.DirectionDown)
            pressKey(Key.F3)
        }

        assertEquals(
            listOf(
                KeyStroke(KeyCode.S, setOf(KeyModifier.META)),
                KeyStroke(KeyCode.F, setOf(KeyModifier.CTRL, KeyModifier.SHIFT)),
                KeyStroke(KeyCode.DOWN),
                KeyStroke(KeyCode.F3),
            ),
            strokes,
        )
    }

    @Test
    fun `US Shift+slash stays SLASH with SHIFT`() = runComposeUiTest {
        val strokes = recordStrokes()

        onNodeWithTag(target).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Slash) } }
        onNodeWithTag(target).performKeyPress(keyDown(Key.Slash, '?', shift = true))

        assertEquals(List(2) { KeyStroke(KeyCode.SLASH, setOf(KeyModifier.SHIFT)) }, strokes)
    }

    @Test
    fun `German Shift+7 producing a slash arrives as SLASH without SHIFT`() = runComposeUiTest {
        val strokes = recordStrokes()

        onNodeWithTag(target).performKeyPress(keyDown(Key.Seven, '/', shift = true))

        assertEquals(listOf(KeyStroke(KeyCode.SLASH)), strokes)
    }

    @Test
    fun `German Cmd+Shift+7 arrives as SLASH with META`() = runComposeUiTest {
        val strokes = recordStrokes()

        onNodeWithTag(target).performKeyPress(keyDown(Key.Seven, '/', shift = true, meta = true))

        assertEquals(listOf(KeyStroke(KeyCode.SLASH, setOf(KeyModifier.META))), strokes)
    }

    @Test
    fun `key-up events and lone modifier presses produce no stroke`() = runComposeUiTest {
        val strokes = recordStrokes()

        onNodeWithTag(target).performKeyInput {
            pressKey(Key.ShiftLeft)
            pressKey(Key.MetaLeft)
        }
        onNodeWithTag(target).performKeyPress(KeyEvent(Key.S, KeyEventType.KeyUp, codePoint = 's'.code))

        assertEquals(emptyList(), strokes)
    }

    private fun keyDown(key: Key, char: Char, shift: Boolean = false, meta: Boolean = false): KeyEvent =
        KeyEvent(key, KeyEventType.KeyDown, codePoint = char.code, isShiftPressed = shift, isMetaPressed = meta)
}
