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
package io.karpfen.ide.ui.workbench.bridges.view

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.bridges.BridgeDraft
import io.karpfen.ide.ui.workbench.bridges.BridgeFormState
import io.karpfen.ide.ui.workbench.bridges.Follows
import io.karpfen.ide.ui.workbench.bridges.InboundDraft
import io.karpfen.ide.ui.workbench.bridges.OutboundDraft
import io.karpfen.ide.ui.workbench.bridges.PolicyDraft
import io.karpfen.ide.ui.workbench.bridges.PolicyKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Headless tests of [BridgeFormLayer]. They cover that the form shows a draft, that typing and the chips change it,
 * and that Save waits until nothing is wrong.
 */
@OptIn(ExperimentalTestApi::class)
class BridgeFormViewUiTest {

    private val file: Path = Path.of("/ws/door.kbridge.json").toAbsolutePath()

    private fun form(draft: BridgeDraft = BridgeDraft(), problems: List<String> = emptyList()) =
        BridgeFormState(file, "door.kbridge.json", isNew = false, fileProblems = problems, initial = draft, loadedText = "")

    @Test
    fun `a new route keeps Save disabled until it has an address and an event`() = runComposeUiTest {
        val form = form()
        var saved = 0
        setContent { KarpfenTheme(darkTheme = false) { BridgeFormLayer(form, onSave = { saved++ }, onClose = {}) } }

        onNodeWithText("Bridge door.kbridge.json").assertIsDisplayed()
        onNodeWithTag(BridgeFormTestTags.SAVE).assertIsEnabled()

        onNodeWithTag(BridgeFormTestTags.ADD_INBOUND).performScrollTo().performClick()
        onNodeWithTag(BridgeFormTestTags.SAVE).assertIsNotEnabled()
        onNodeWithText("Inbound route 1 needs an address.").assertIsDisplayed()

        onNodeWithTag(BridgeFormTestTags.inbound(0, "from")).performScrollTo().performTextInput("/press")
        onNodeWithTag(BridgeFormTestTags.inbound(0, "event")).performScrollTo().performTextInput("switch/press")
        onNodeWithTag(BridgeFormTestTags.SAVE).assertIsEnabled().performClick()

        assertEquals(listOf(InboundDraft("/press", "switch/press")), form.draft.value.inbound)
        assertEquals(1, saved)
    }

    @Test
    fun `the chips choose the kind, what an outbound route follows and its policy`() = runComposeUiTest {
        val form = form(BridgeDraft(settings = mapOf("port" to "9000"), outbound = listOf(OutboundDraft(Follows.PROPERTY, "bulb/watts", "home/watts"))))
        setContent { KarpfenTheme(darkTheme = true) { BridgeFormLayer(form, onSave = {}, onClose = {}) } }

        onNodeWithTag(BridgeFormTestTags.kind("mqtt")).performScrollTo().performClick()
        onNodeWithTag(BridgeFormTestTags.setting("retain")).performScrollTo().performClick()
        onNodeWithTag(BridgeFormTestTags.outbound(0, "state")).performScrollTo().performClick()
        onNodeWithTag(BridgeFormTestTags.outbound(0, "number") + ".sample").performScrollTo().performClick()
        onNodeWithTag(BridgeFormTestTags.outbound(0, "number")).performScrollTo().performTextInput("500")

        val draft = form.draft.value
        assertEquals(BridgeKind.MQTT, draft.kind)
        assertEquals(mapOf("retain" to "true"), draft.settings, "the port of the HTTP bridge is gone, retain is on")
        assertEquals(listOf(OutboundDraft(Follows.STATE, "bulb/watts", "home/watts", PolicyDraft(PolicyKind.SAMPLE, "500"))), draft.outbound)
    }

    @Test
    fun `the problems of the file are shown, Cancel closes, and no form shows nothing`() = runComposeUiTest {
        var closed = 0
        var shown: BridgeFormState? = form(problems = listOf("inbound[1] needs \"from\"."))
        setContent { KarpfenTheme(darkTheme = false) { BridgeFormLayer(shown, onSave = {}, onClose = { closed++ }) } }

        onNodeWithText("inbound[1] needs \"from\".").assertIsDisplayed()
        onNodeWithTag(BridgeFormTestTags.CANCEL).performClick()
        assertEquals(1, closed)

        shown = null
        setContent { KarpfenTheme(darkTheme = false) { BridgeFormLayer(shown, onSave = {}, onClose = {}) } }
        onNodeWithTag(BridgeFormTestTags.FORM).assertDoesNotExist()
    }
}
