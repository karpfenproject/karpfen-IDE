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
package io.karpfen.ide.ui.editor.model.action

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.foundation.keys.KeyCode
import io.karpfen.ide.ui.foundation.keys.KeyModifier
import io.karpfen.ide.ui.foundation.keys.KeyShortcut
import io.karpfen.ide.ui.foundation.keys.KeyStroke
import io.karpfen.ide.ui.foundation.keys.displayText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The action registry, the default actions and the platform keymaps. */
class EditorActionRegistryTest {

    /** An action with a fixed enablement that counts its runs. */
    private class CountingAction(override val id: String, private val enabled: Boolean = true) : EditorAction {
        /** How often the action ran. */
        var runs = 0
        override fun isEnabled(context: EditorActionContext): Boolean = enabled
        override fun execute(context: EditorActionContext): Boolean {
            runs++
            return true
        }
    }

    @Test
    fun `the registry finds actions by id and runs only enabled ones`() {
        val enabled = CountingAction("a")
        val disabled = CountingAction("b", enabled = false)
        val registry = EditorActionRegistry(listOf(enabled, disabled))
        val context = TestEditor("")

        assertSame(enabled, registry.get("a"))
        assertNull(registry.get("missing"))
        assertTrue(registry.execute("a", context))
        assertFalse(registry.execute("b", context))
        assertFalse(registry.execute("missing", context))
        assertEquals(1, enabled.runs)
        assertEquals(0, disabled.runs)
        assertEquals(listOf(enabled, disabled), registry.actions.toList())
    }

    @Test
    fun `the registry rejects duplicate ids`() {
        assertFailsWith<IllegalArgumentException> { EditorActionRegistry(listOf(CountingAction("a"), CountingAction("a"))) }
    }

    @Test
    fun `every action id but typing has exactly one default action`() {
        val declared = EditorActionTitles.titles.keys - EditorActionIds.TYPE_TEXT
        assertEquals(declared, defaultEditorActions().map { it.id }.toSet())
        assertEquals(EditorActionIds.TYPE_TEXT, typeTextAction("x").id)
    }

    @Test
    fun `every bound action exists and every stroke has one action on every platform`() {
        val ids = defaultEditorActions().map { it.id }.toSet()
        for (platform in HostPlatform.entries) {
            val bindings = defaultKeyBindings(platform)
            val strokes = bindings.map { it.shortcut.strokeFor(platform) }
            assertEquals(strokes.size, strokes.toSet().size, "duplicate strokes on $platform")
            bindings.forEach { assertTrue(it.actionId in ids, "${it.actionId} on $platform") }
            val keymap = defaultEditorKeymap(platform)
            assertEquals(platform, keymap.platform)
            bindings.forEach { assertEquals(it.actionId, keymap.actionFor(it.shortcut.strokeFor(platform))) }
        }
    }

    @Test
    fun `the documented bindings exist on macOS`() {
        val keymap = defaultEditorKeymap(HostPlatform.MAC_OS)
        fun action(key: KeyCode, vararg modifiers: KeyModifier) = keymap.actionFor(KeyStroke(key, modifiers.toSet()))

        assertEquals(EditorActionIds.CARET_LEFT, action(KeyCode.LEFT))
        assertEquals(EditorActionIds.SELECT_DOWN, action(KeyCode.DOWN, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.CARET_WORD_LEFT, action(KeyCode.LEFT, KeyModifier.ALT))
        assertEquals(EditorActionIds.SELECT_WORD_RIGHT, action(KeyCode.RIGHT, KeyModifier.ALT, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.CARET_LINE_START, action(KeyCode.HOME))
        assertEquals(EditorActionIds.CARET_LINE_END, action(KeyCode.RIGHT, KeyModifier.META))
        assertEquals(EditorActionIds.CARET_DOCUMENT_START, action(KeyCode.UP, KeyModifier.META))
        assertEquals(EditorActionIds.SELECT_PAGE_DOWN, action(KeyCode.PAGE_DOWN, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.SELECT_ALL, action(KeyCode.A, KeyModifier.META))
        assertEquals(EditorActionIds.ENTER, action(KeyCode.ENTER))
        assertEquals(EditorActionIds.BACKSPACE, action(KeyCode.BACKSPACE))
        assertEquals(EditorActionIds.DELETE, action(KeyCode.DELETE))
        assertEquals(EditorActionIds.DELETE_WORD_LEFT, action(KeyCode.BACKSPACE, KeyModifier.ALT))
        assertEquals(EditorActionIds.DELETE_LINE, action(KeyCode.BACKSPACE, KeyModifier.META))
        assertEquals(EditorActionIds.DUPLICATE, action(KeyCode.D, KeyModifier.META))
        assertEquals(EditorActionIds.MOVE_LINE_UP, action(KeyCode.UP, KeyModifier.ALT, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.INDENT, action(KeyCode.TAB))
        assertEquals(EditorActionIds.OUTDENT, action(KeyCode.TAB, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.TOGGLE_COMMENT, action(KeyCode.SLASH, KeyModifier.META))
        assertEquals(EditorActionIds.COPY, action(KeyCode.C, KeyModifier.META))
        assertEquals(EditorActionIds.CUT, action(KeyCode.X, KeyModifier.META))
        assertEquals(EditorActionIds.PASTE, action(KeyCode.V, KeyModifier.META))
        assertEquals(EditorActionIds.UNDO, action(KeyCode.Z, KeyModifier.META))
        assertEquals(EditorActionIds.REDO, action(KeyCode.Z, KeyModifier.META, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.FIND, action(KeyCode.F, KeyModifier.META))
        assertEquals(EditorActionIds.REPLACE, action(KeyCode.R, KeyModifier.META))
        assertEquals(EditorActionIds.FIND_NEXT, action(KeyCode.G, KeyModifier.META))
        assertEquals(EditorActionIds.FIND_PREVIOUS, action(KeyCode.G, KeyModifier.META, KeyModifier.SHIFT))
        assertEquals(EditorActionIds.GO_TO_LINE, action(KeyCode.L, KeyModifier.META))
        assertEquals(EditorActionIds.COMPLETION, action(KeyCode.M, KeyModifier.CTRL))
        assertEquals(EditorActionIds.GO_TO_DEFINITION, action(KeyCode.B, KeyModifier.META))
        assertNull(action(KeyCode.Y, KeyModifier.CTRL))
    }

    @Test
    fun `the documented bindings exist on Windows and Linux`() {
        for (platform in listOf(HostPlatform.WINDOWS, HostPlatform.LINUX)) {
            val keymap = defaultEditorKeymap(platform)
            fun action(key: KeyCode, vararg modifiers: KeyModifier) = keymap.actionFor(KeyStroke(key, modifiers.toSet()))

            assertEquals(EditorActionIds.CARET_WORD_RIGHT, action(KeyCode.RIGHT, KeyModifier.CTRL))
            assertEquals(EditorActionIds.SELECT_WORD_LEFT, action(KeyCode.LEFT, KeyModifier.CTRL, KeyModifier.SHIFT))
            assertEquals(EditorActionIds.CARET_DOCUMENT_END, action(KeyCode.END, KeyModifier.CTRL))
            assertEquals(EditorActionIds.SELECT_LINE_START, action(KeyCode.HOME, KeyModifier.SHIFT))
            assertEquals(EditorActionIds.DELETE_WORD_RIGHT, action(KeyCode.DELETE, KeyModifier.CTRL))
            assertEquals(EditorActionIds.DELETE_LINE, action(KeyCode.Y, KeyModifier.CTRL))
            assertEquals(EditorActionIds.TOGGLE_COMMENT, action(KeyCode.SLASH, KeyModifier.CTRL))
            assertEquals(EditorActionIds.FIND_NEXT, action(KeyCode.F3))
            assertEquals(EditorActionIds.FIND_PREVIOUS, action(KeyCode.F3, KeyModifier.SHIFT))
            assertEquals(EditorActionIds.GO_TO_LINE, action(KeyCode.G, KeyModifier.CTRL))
            assertEquals(EditorActionIds.COMPLETION, action(KeyCode.M, KeyModifier.CTRL))
            assertEquals(EditorActionIds.REDO, action(KeyCode.Z, KeyModifier.CTRL, KeyModifier.SHIFT))
            assertNull(action(KeyCode.L, KeyModifier.META), platform.name)
        }
    }

    @Test
    fun `completion is Ctrl+M on every platform and also Ctrl+Space on Windows`() {
        val ctrlM = KeyStroke(KeyCode.M, setOf(KeyModifier.CTRL))
        val ctrlSpace = KeyStroke(KeyCode.SPACE, setOf(KeyModifier.CTRL))
        val completionStrokes = HostPlatform.entries.associateWith { platform ->
            val keymap = defaultEditorKeymap(platform)
            listOf(ctrlM, ctrlSpace).filter { keymap.actionFor(it) == EditorActionIds.COMPLETION }
        }

        assertEquals(
            mapOf(
                HostPlatform.MAC_OS to listOf(ctrlM),
                HostPlatform.WINDOWS to listOf(ctrlM, ctrlSpace),
                HostPlatform.LINUX to listOf(ctrlM),
            ),
            completionStrokes,
        )
        assertNull(defaultEditorKeymap(HostPlatform.MAC_OS).actionFor(ctrlSpace), "macOS keeps Ctrl+Space for input sources")
        assertNull(defaultEditorKeymap(HostPlatform.LINUX).actionFor(ctrlSpace), "Linux keeps Ctrl+Space for input methods")
        assertNull(defaultEditorKeymap(HostPlatform.MAC_OS).actionFor(KeyStroke(KeyCode.M, setOf(KeyModifier.META))), "Cmd+M minimizes")
    }

    @Test
    fun `the completion shortcut a menu shows is Ctrl+M on every platform, in the platform's notation`() {
        val shown = HostPlatform.entries.associateWith { platform ->
            val shortcut = assertNotNull(defaultEditorKeymap(platform).shortcutFor(EditorActionIds.COMPLETION), "$platform")
            assertEquals(KeyShortcut(KeyCode.M, ctrl = true), shortcut, "the first binding, Ctrl+M, is the one menus show")
            shortcut.displayText(platform)
        }

        assertEquals(mapOf(HostPlatform.MAC_OS to "⌃M", HostPlatform.WINDOWS to "Ctrl+M", HostPlatform.LINUX to "Ctrl+M"), shown)
    }

    @Test
    fun `menus get the first shortcut of an action`() {
        val mac = defaultEditorKeymap(HostPlatform.MAC_OS)
        assertEquals(KeyShortcut(KeyCode.Z, primary = true), mac.shortcutFor(EditorActionIds.UNDO))
        assertEquals(KeyShortcut(KeyCode.SLASH, primary = true), mac.shortcutFor(EditorActionIds.TOGGLE_COMMENT))
        assertEquals(KeyShortcut(KeyCode.L, primary = true), mac.shortcutFor(EditorActionIds.GO_TO_LINE))
        assertEquals(KeyShortcut(KeyCode.F3), defaultEditorKeymap(HostPlatform.LINUX).shortcutFor(EditorActionIds.FIND_NEXT))
        assertEquals(KeyShortcut(KeyCode.Y, primary = true), defaultEditorKeymap(HostPlatform.WINDOWS).shortcutFor(EditorActionIds.DELETE_LINE))
        assertNull(mac.shortcutFor(EditorActionIds.TYPE_TEXT))
        assertNull(mac.shortcutFor("unknown"))
    }

    @Test
    fun `every menu and context menu action has a shortcut on every platform`() {
        val menuActions = listOf(
            EditorActionIds.UNDO, EditorActionIds.REDO, EditorActionIds.CUT, EditorActionIds.COPY, EditorActionIds.PASTE,
            EditorActionIds.SELECT_ALL, EditorActionIds.FIND, EditorActionIds.REPLACE, EditorActionIds.FIND_NEXT,
            EditorActionIds.FIND_PREVIOUS, EditorActionIds.TOGGLE_COMMENT, EditorActionIds.DUPLICATE,
            EditorActionIds.GO_TO_LINE, EditorActionIds.GO_TO_DEFINITION, EditorActionIds.COMPLETION,
        )
        for (platform in HostPlatform.entries) {
            val keymap = defaultEditorKeymap(platform)
            menuActions.forEach { assertTrue(keymap.shortcutFor(it) != null, "$it on $platform") }
        }
    }
}
