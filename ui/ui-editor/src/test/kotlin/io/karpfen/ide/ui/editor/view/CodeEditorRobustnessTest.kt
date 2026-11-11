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
package io.karpfen.ide.ui.editor.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.fixtures.Examples
import io.karpfen.ide.core.document.fixtures.Mutations
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.state.FakeLanguageServices
import io.karpfen.ide.ui.editor.state.FindBarMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Robustness of the composed editor, with fixed seeds. The text is replaced by deterministic mutations of a KStates
 * example while the find bar highlights matches, the view renders, clicks land at random places and the overlay
 * geometry is queried. Nothing throws, and the caret stays inside the text.
 */
@OptIn(ExperimentalTestApi::class)
class CodeEditorRobustnessTest {

    @Test
    fun `rendering and hit-testing survive mutated texts`() = runComposeUiTest {
        val example = Examples.files(KarpfenLanguage.KSTATES).first().text
        val ui = EditorUi(this, FakeLanguageServices())
        ui.compose(example)
        ui.state.find.setQuery(FindQuery("STATE"))
        ui.state.overlays.openFind(FindBarMode.FIND)
        val random = Random(3)

        Mutations.random(example, seed = 5, count = 40).forEachIndexed { index, mutation ->
            ui.text.replaceAll(mutation.text, EditOrigin.External)
            ui.state.viewport.scrollBy(0f, random.nextInt(-200, 400).toFloat())
            ui.textArea.performMouseInput { click(Offset(random.nextInt(0, 500).toFloat(), random.nextInt(0, 280).toFloat())) }
            waitForIdle()
            ui.host.caretBounds
            ui.host.boundsOf(random.nextInt(0, ui.text.snapshot.value.length + 1))
            if (index % 8 == 0) ui.textArea.captureToImage()
            assertTrue(ui.state.caret.primary.position <= ui.text.snapshot.value.length, "$mutation")
        }
    }
}
