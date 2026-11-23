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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.workspace.document.ExternalChange
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.workbench.testing.ClosingSpyEditor
import io.karpfen.ide.ui.workbench.testing.FakeOpenDocument
import io.karpfen.ide.ui.workbench.testing.editorOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests [EditorTabsState]. It covers the order, activation, removal and moves of tabs, the dirty and conflict flags of
 * the documents, and the panes: splitting, views of one document in several panes, the flows of the active pane, the
 * focus, resizing, the replacement of a renamed document and clearing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorTabsStateTest {

    private val root: Path = Path.of("/ws").toAbsolutePath()

    /** An [EditorTabsState] on the test's scheduler with the fake documents and spy editors of the tabs it opened. */
    private class Fixture(test: TestScope, val root: Path) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val tabs = EditorTabsState(scope)
        val documents = LinkedHashMap<String, FakeOpenDocument>()
        val editors = LinkedHashMap<String, ClosingSpyEditor>()

        /** Opens a tab for an empty document [name] below [root]. The tab goes to [index] if one is given. */
        fun open(name: String, index: Int? = null): FakeOpenDocument {
            val document = FakeOpenDocument(root.resolve(name), "", scope, dispatcher)
            val editor = editorOf(document, scope, dispatcher)
            documents[name] = document
            editors[name] = editor
            tabs.open(document, editor, root, index)
            return document
        }

        /** The tab titles of the active pane in display order. */
        fun titles(): List<String> = tabs.tabs.value.map { it.title }

        /** Splits [pane] towards [side] with a new spy editor of [document], and returns the new pane. */
        fun split(pane: PaneId, side: SplitSide, document: FakeOpenDocument): PaneId? =
            tabs.split(pane, side, editorOf(document, scope, dispatcher))
    }

    /** Runs [block] with a fresh [Fixture] and cancels its scope afterwards. */
    private fun fixture(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this, root)
        try {
            block(fixture)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun `tabs open after the active one, at an index, and an open path is only activated`() = fixture { f ->
        f.open("a.kstates")
        f.open("b.kmeta")
        f.tabs.activate(root.resolve("a.kstates"))
        f.open("c.kmodel")
        assertEquals(listOf("a.kstates", "c.kmodel", "b.kmeta"), f.titles())
        f.open("d.kstates", index = 0)
        assertEquals(listOf("d.kstates", "a.kstates", "c.kmodel", "b.kmeta"), f.titles())

        f.tabs.open(f.documents.getValue("b.kmeta"), f.editors.getValue("b.kmeta"), root)
        assertEquals(4, f.tabs.tabs.value.size)
        assertEquals(root.resolve("b.kmeta"), f.tabs.active.value)
        assertSame(f.editors.getValue("b.kmeta"), f.tabs.activeEditor.value)
        assertSame(f.documents.getValue("c.kmodel"), f.tabs.documentFor(root.resolve("c.kmodel")))
        assertEquals(2, f.tabs.indexOf(root.resolve("c.kmodel")))
    }

    @Test
    fun `tab values carry title, relative tooltip and the file's kind`() = fixture { f ->
        f.open("sub/robot.kstates")
        val tab = f.tabs.tabs.value.single()
        val expected = EditorTab(root.resolve("sub/robot.kstates"), "robot.kstates", "sub/robot.kstates", FileKind.KSTATES, false)
        assertEquals(expected, tab)
        assertFalse(tab.hasConflict)

        f.open("EVENTS.kmeta")
        f.open("robot.kenv.json")
        assertEquals(
            listOf(FileKind.KSTATES, FileKind.EVENTS_KMETA, FileKind.ENV_DESCRIPTOR),
            f.tabs.tabs.value.map { it.kind },
            "the kind of the workspace's file rule, the event metamodel included",
        )
    }

    @Test
    fun `removing a tab closes its editor and activates the right neighbour, else the left one`() = fixture { f ->
        listOf("a.kmeta", "b.kmeta", "c.kmeta").forEach { f.open(it) }
        f.tabs.activate(root.resolve("b.kmeta"))

        f.tabs.remove(f.tabs.activePane.value,root.resolve("b.kmeta"))
        assertTrue(f.editors.getValue("b.kmeta").closed)
        assertEquals(root.resolve("c.kmeta"), f.tabs.active.value)
        f.tabs.remove(f.tabs.activePane.value,root.resolve("c.kmeta"))
        assertEquals(root.resolve("a.kmeta"), f.tabs.active.value)
        f.tabs.remove(f.tabs.activePane.value,root.resolve("a.kmeta"))
        assertNull(f.tabs.active.value)
        assertNull(f.tabs.activeEditor.value)

        f.tabs.remove(f.tabs.activePane.value,root.resolve("a.kmeta"))
        assertTrue(f.tabs.tabs.value.isEmpty())
    }

    @Test
    fun `removing an inactive tab keeps the active one`() = fixture { f ->
        listOf("a.kmeta", "b.kmeta").forEach { f.open(it) }
        f.tabs.remove(f.tabs.activePane.value,root.resolve("a.kmeta"))
        assertEquals(root.resolve("b.kmeta"), f.tabs.active.value)
    }

    @Test
    fun `tabs move within bounds`() = fixture { f ->
        listOf("a.kmeta", "b.kmeta", "c.kmeta").forEach { f.open(it) }
        f.tabs.move(0, 2)
        assertEquals(listOf("b.kmeta", "c.kmeta", "a.kmeta"), f.titles())
        f.tabs.move(0, 7)
        f.tabs.move(-1, 0)
        assertEquals(listOf("b.kmeta", "c.kmeta", "a.kmeta"), f.titles())
    }

    @Test
    fun `tabs follow the dirty flag and the conflict of their document`() = fixture { f ->
        val document = f.open("a.kstates")
        document.isDirty.value = true
        advanceUntilIdle()
        assertTrue(f.tabs.tabs.value.single().isDirty)

        document.conflict.value = ExternalChange(document.path, ExternalChangeKind.DELETED)
        advanceUntilIdle()
        assertEquals(ExternalChangeKind.DELETED, f.tabs.tabs.value.single().conflict)
        assertTrue(f.tabs.tabs.value.single().hasConflict)

        f.tabs.remove(f.tabs.activePane.value, document.path)
        document.isDirty.value = false
        advanceUntilIdle()
        assertTrue(f.tabs.tabs.value.isEmpty(), "a removed tab no longer follows its document")
    }

    @Test
    fun `activating an unknown path changes nothing and lookups answer null`() = fixture { f ->
        f.open("a.kmeta")
        f.tabs.activate(root.resolve("x.kmeta"))
        assertEquals(root.resolve("a.kmeta"), f.tabs.active.value)
        assertNull(f.tabs.editorFor(root.resolve("x.kmeta")))
        assertNull(f.tabs.documentFor(root.resolve("x.kmeta")))
        assertEquals(-1, f.tabs.indexOf(root.resolve("x.kmeta")))
    }

    @Test
    fun `a split shows the active file in a new active pane with its own editor on the same document`() = fixture { f ->
        val document = f.open("a.kstates")
        f.open("b.kmeta")
        f.tabs.activate(document.path)
        f.tabs.setViewMode(document.path, EditorViewMode.DIAGRAM)
        val first = f.tabs.activePane.value

        val second = assertNotNull(f.split(first, SplitSide.RIGHT, document))

        assertEquals(second, f.tabs.activePane.value)
        assertEquals(listOf(first, second), f.tabs.layout.value.panes.map { it.id })
        assertEquals(listOf("a.kstates"), f.titles(), "the tabs of the active pane")
        assertEquals(document.path, f.tabs.active.value)
        val editors = listOf(f.tabs.editorFor(first, document.path), f.tabs.editorFor(second, document.path))
        assertNotSame(editors[0], editors[1])
        assertSame(editors[1], f.tabs.activeEditor.value)
        assertSame(editors[0]?.session, editors[1]?.session, "one document session")
        assertSame(document, f.tabs.documentFor(document.path))
        assertEquals(listOf(document.path, root.resolve("b.kmeta")), f.tabs.documents().map { it.path }, "each document once")
        assertEquals(EditorViewMode.DIAGRAM, f.tabs.panes.value.getValue(second).viewMode, "the view mode is copied")

        f.tabs.setViewMode(second, document.path, EditorViewMode.TEXT)
        assertEquals(EditorViewMode.DIAGRAM, f.tabs.panes.value.getValue(first).viewMode, "view modes are per pane")
        assertEquals(EditorViewMode.TEXT, f.tabs.viewModes.value[document.path])
        assertNull(f.split(PaneId(99), SplitSide.LEFT, document), "an unknown pane is not split")
    }

    @Test
    fun `the flows without a pane follow the active pane`() = fixture { f ->
        val document = f.open("a.kstates")
        val first = f.tabs.activePane.value
        val second = assertNotNull(f.split(first, SplitSide.DOWN, document))
        f.open("b.kmeta")
        assertEquals(listOf("a.kstates", "b.kmeta"), f.titles(), "opening goes into the active pane")

        f.tabs.activatePane(first)
        assertEquals(listOf("a.kstates"), f.titles())
        assertEquals(document.path, f.tabs.active.value)
        assertSame(f.tabs.editorFor(first, document.path), f.tabs.activeEditor.value)
        assertEquals(0, f.tabs.indexOf(document.path))
        assertEquals(-1, f.tabs.indexOf(root.resolve("b.kmeta")))

        f.tabs.activate(second, root.resolve("b.kmeta"))
        assertEquals(second, f.tabs.activePane.value)
        assertEquals(root.resolve("b.kmeta"), f.tabs.active.value)
        val states = f.tabs.panes.value
        assertEquals(listOf(document.path), states.getValue(first).tabs.map { it.path })
        assertEquals(root.resolve("b.kmeta"), states.getValue(second).active)
    }

    @Test
    fun `a removed view keeps the document while another pane shows it, and an emptied pane collapses`() = fixture { f ->
        val document = f.open("a.kstates")
        val first = f.tabs.activePane.value
        val second = assertNotNull(f.split(first, SplitSide.RIGHT, document))
        val secondEditor = assertNotNull(f.tabs.editorFor(second, document.path))

        f.tabs.remove(second, document.path)
        assertTrue((secondEditor as ClosingSpyEditor).closed)
        assertFalse(f.editors.getValue("a.kstates").closed)
        assertSame(document, f.tabs.documentFor(document.path), "the first pane still shows it")
        assertEquals(listOf(first), f.tabs.layout.value.panes.map { it.id }, "the empty pane is gone")
        assertEquals(first, f.tabs.activePane.value, "its neighbour is active")

        document.isDirty.value = true
        advanceUntilIdle()
        assertTrue(f.tabs.tabs.value.single().isDirty, "the remaining tab still follows the document")

        f.tabs.remove(first, document.path)
        assertNull(f.tabs.documentFor(document.path), "the last view forgets the document")
        assertEquals(listOf(first), f.tabs.layout.value.panes.map { it.id }, "the last pane stays, empty")
        assertTrue(f.tabs.tabs.value.isEmpty())
    }

    @Test
    fun `the pane of an editor that gains the focus becomes active`() = fixture { f ->
        val document = f.open("a.kstates")
        val first = f.tabs.activePane.value
        f.split(first, SplitSide.RIGHT, document)
        advanceUntilIdle()

        assertNotNull(f.tabs.editorFor(first, document.path)).onFocusChanged(true)
        advanceUntilIdle()

        assertEquals(first, f.tabs.activePane.value)
    }

    @Test
    fun `focus next and previous cycle through the panes and ask their editors for the focus`() = fixture { f ->
        val document = f.open("a.kstates")
        val first = f.tabs.activePane.value
        val second = assertNotNull(f.split(first, SplitSide.RIGHT, document))
        val third = assertNotNull(f.split(second, SplitSide.DOWN, document))

        f.tabs.focusNextPane()
        assertEquals(first, f.tabs.activePane.value, "after the last pane comes the first")
        assertTrue(assertNotNull(f.tabs.editorFor(first, document.path)).focusRequested.value)
        f.tabs.focusPreviousPane()
        assertEquals(third, f.tabs.activePane.value)
        f.tabs.focusPreviousPane()
        assertEquals(second, f.tabs.activePane.value)
        assertTrue(assertNotNull(f.tabs.editorFor(second, document.path)).focusRequested.value)
    }

    @Test
    fun `a resize changes the share of the split`() = fixture { f ->
        val document = f.open("a.kstates")
        f.split(f.tabs.activePane.value, SplitSide.UP, document)
        val split = assertIs<PaneNode.Split>(f.tabs.layout.value.root)

        f.tabs.resize(split.id, 0.25f)

        assertEquals(0.25f, assertIs<PaneNode.Split>(f.tabs.layout.value.root).share)
    }

    @Test
    fun `a replaced document takes the place of the old one in every pane with new editors`() = fixture { f ->
        val document = f.open("a.kstates")
        f.open("b.kmeta")
        f.tabs.activate(document.path)
        val first = f.tabs.activePane.value
        val second = assertNotNull(f.split(first, SplitSide.RIGHT, document))
        f.tabs.setViewMode(second, document.path, EditorViewMode.SPLIT)
        val oldEditors = f.tabs.editorsOf(document.path)
        val renamed = FakeOpenDocument(root.resolve("c.kstates"), "", f.scope, f.dispatcher)

        f.tabs.replace(document.path, renamed, root) { editorOf(renamed, f.scope, f.dispatcher) }

        assertTrue(oldEditors.all { (it as ClosingSpyEditor).closed })
        assertEquals(listOf(listOf("c.kstates", "b.kmeta"), listOf("c.kstates")), f.tabs.panes.value.values.map { pane -> pane.tabs.map { it.title } })
        assertEquals(second, f.tabs.activePane.value)
        assertEquals(renamed.path, f.tabs.active.value)
        assertSame(renamed.session, f.tabs.activeEditor.value?.session)
        assertEquals(EditorViewMode.SPLIT, f.tabs.viewModes.value[renamed.path])
        assertNull(f.tabs.documentFor(document.path))
    }

    @Test
    fun `reveal activates a tab in the active pane, else in the first pane that has it`() = fixture { f ->
        val document = f.open("a.kstates")
        val first = f.tabs.activePane.value
        val second = assertNotNull(f.split(first, SplitSide.RIGHT, document))
        f.open("b.kmeta")
        f.tabs.activatePane(first)

        f.tabs.reveal(root.resolve("b.kmeta"))
        assertEquals(second, f.tabs.activePane.value)
        assertEquals(root.resolve("b.kmeta"), f.tabs.active.value)

        f.tabs.reveal(document.path)
        assertEquals(second, f.tabs.activePane.value, "the active pane has a tab of it")
        assertEquals(document.path, f.tabs.active.value)
    }

    @Test
    fun `clearing closes every view and leaves one empty pane`() = fixture { f ->
        val document = f.open("a.kstates")
        f.split(f.tabs.activePane.value, SplitSide.RIGHT, document)
        val editors = f.tabs.allEditors()

        f.tabs.clear()

        assertTrue(editors.all { (it as ClosingSpyEditor).closed })
        assertEquals(1, f.tabs.layout.value.panes.size)
        assertTrue(f.tabs.tabs.value.isEmpty())
        assertTrue(f.tabs.documents().isEmpty())
        assertNull(f.tabs.activeEditor.value)
    }
}
