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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [EditorTabsState]: order, activation, removal, moves and the documents' dirty and conflict flags. */
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

        /** Opens a tab for an empty document [name] below [root], at [index] if given. */
        fun open(name: String, index: Int? = null): FakeOpenDocument {
            val document = FakeOpenDocument(root.resolve(name), "", scope, dispatcher)
            val editor = editorOf(document, scope, dispatcher)
            documents[name] = document
            editors[name] = editor
            tabs.open(document, editor, root, index)
            return document
        }

        /** The tab titles in display order. */
        fun titles(): List<String> = tabs.tabs.value.map { it.title }
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

        f.tabs.remove(root.resolve("b.kmeta"))
        assertTrue(f.editors.getValue("b.kmeta").closed)
        assertEquals(root.resolve("c.kmeta"), f.tabs.active.value)
        f.tabs.remove(root.resolve("c.kmeta"))
        assertEquals(root.resolve("a.kmeta"), f.tabs.active.value)
        f.tabs.remove(root.resolve("a.kmeta"))
        assertNull(f.tabs.active.value)
        assertNull(f.tabs.activeEditor.value)

        f.tabs.remove(root.resolve("a.kmeta"))
        assertTrue(f.tabs.tabs.value.isEmpty())
    }

    @Test
    fun `removing an inactive tab keeps the active one`() = fixture { f ->
        listOf("a.kmeta", "b.kmeta").forEach { f.open(it) }
        f.tabs.remove(root.resolve("a.kmeta"))
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

        f.tabs.remove(document.path)
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
}
