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
package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.ui.editor.model.action.EditorActionIds
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Robustness of the editor state (fixed seeds): external replacements of the text by deterministic mutations of a
 * KStates example, interleaved with key strokes, pointer gestures, hovering and typing, never throw; the published
 * highlights always refer to a text version that has them in bounds and the carets stay inside the text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorStateRobustnessTest {

    private val example = Examples.files(KarpfenLanguage.KSTATES).first().text

    private val strokes = listOf(
        KeyStroke(KeyCode.ENTER), KeyStroke(KeyCode.BACKSPACE), KeyStroke(KeyCode.DELETE), KeyStroke(KeyCode.TAB),
        KeyStroke(KeyCode.UP, setOf(KeyModifier.SHIFT)), KeyStroke(KeyCode.PAGE_DOWN), KeyStroke(KeyCode.END),
        KeyStroke(KeyCode.SLASH, setOf(KeyModifier.META)), KeyStroke(KeyCode.D, setOf(KeyModifier.META)),
        KeyStroke(KeyCode.Z, setOf(KeyModifier.META)), KeyStroke(KeyCode.SPACE, setOf(KeyModifier.CTRL)),
    )

    @Test
    fun `mutations, intents and background work never throw and stay consistent`() = editorTest(example) { harness ->
        val random = Random(7)
        harness.layOut(visibleLines = 20)
        for (mutation in Mutations.random(example, seed = 11, count = 150)) {
            harness.text.replaceAll(mutation.text, EditOrigin.External)
            val length = harness.text.snapshot.value.length
            repeat(3) {
                when (random.nextInt(6)) {
                    0 -> harness.state.handleKey(strokes.random(random))
                    1 -> harness.state.pointerPressed(random.nextInt(0, length + 5), random.nextInt(1, 4), emptySet())
                    2 -> harness.state.pointerDragged(random.nextInt(0, length + 5))
                    3 -> harness.state.pointerHover(random.nextInt(0, length + 1))
                    4 -> harness.state.type(listOf("x", "{", "\"", "\n", "(").random(random))
                    else -> harness.state.execute(EditorActionIds.SELECT_ALL)
                }
            }
            harness.state.pointerReleased()
            harness.applyScrollRequest()
            advanceUntilIdle()

            val snapshot = harness.text.snapshot.value
            val highlights = harness.state.highlights.value
            assertEquals(snapshot.version, highlights.version, "$mutation: highlights caught up")
            assertTrue(highlights.spans.all { it.range.end <= snapshot.length }, "$mutation: spans in bounds")
            assertTrue(harness.state.caret.primary.position <= snapshot.length, "$mutation: caret in bounds")
        }
    }
}
