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
package io.karpfen.ide.core.workspace

import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.document.SaveResult
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.core.workspace.fixtures.TestWorkspaces
import io.karpfen.ide.core.workspace.fixtures.openDocument
import io.karpfen.ide.core.workspace.fixtures.settle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Covers watching through a fake watcher: the 200 ms debounce, the reload of clean documents and the conflicts of dirty
 * or deleted ones, the ignored watch event of a save, overflows, ignored directories, and refresh without watching.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class WorkspaceWatchTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()
    private val original = "type \"Robot\" \"A robot\" {\n}\n"
    private val changed = "type \"Robot\" \"Changed on disk\" {\n}\n"

    /** Writes [content] to [relative] below the root, creating its directories. */
    private fun file(relative: String, content: String = original): Path =
        root.resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, content) }

    @Test
    fun `changes are debounced by 200 ms, then the tree is rescanned and a clean document reloads`() = runTest(timeout = 60.seconds) {
        val path = file("ctx/robot.kmeta")
        val workspaces = TestWorkspaces(this)
        val workspace = workspaces.open(root, watch = true)
        val document = workspace.openDocument(path)
        settle()
        assertEquals(root, workspaces.watcher.watchedRoot)

        Files.writeString(path, changed)
        val added = file("ctx/added.kmodel", "make object \"r\":\"Robot\" { }\n")
        workspaces.watcher.report(path)
        advanceTimeBy(150.milliseconds)
        workspaces.watcher.report(added, FileChangeKind.CREATED)
        advanceTimeBy(199.milliseconds)
        runCurrent()
        assertEquals(original, document.session.text.snapshot.value.text, "still within the debounce")
        assertNull(workspace.files.value.find(added))

        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(changed, document.session.text.snapshot.value.text)
        assertNotNull(workspace.files.value.find(added))
        assertEquals(listOf(added), workspace.contexts.value.contexts.single().models)
    }

    @Test
    fun `a dirty document gets a conflict, a deleted directory deletes its documents`() = runTest(timeout = 60.seconds) {
        val dirty = file("a/robot.kmeta")
        val inDeletedDirectory = file("b/robot.kmeta")
        val workspaces = TestWorkspaces(this)
        val workspace = workspaces.open(root, watch = true)
        val first = workspace.openDocument(dirty)
        val second = workspace.openDocument(inDeletedDirectory)
        first.type("// mine\n")

        Files.writeString(dirty, changed)
        root.resolve("b").deleteRecursively()
        workspaces.watcher.report(dirty)
        workspaces.watcher.report(root.resolve("b"), FileChangeKind.DELETED)
        settle()

        assertEquals(ExternalChangeKind.MODIFIED, first.conflict.value?.kind)
        assertEquals("// mine\n$original", first.session.text.snapshot.value.text)
        assertEquals(ExternalChangeKind.DELETED, second.conflict.value?.kind)
        assertNull(workspace.files.value.find(root.resolve("b")))
    }

    @Test
    fun `the watch event of a save is ignored and an overflow checks every open document`() = runTest(timeout = 60.seconds) {
        val saved = file("ctx/saved.kmeta")
        val other = file("ctx/other.kmeta")
        val workspaces = TestWorkspaces(this)
        val workspace = workspaces.open(root, watch = true)
        val savedDocument = workspace.openDocument(saved)
        val otherDocument = workspace.openDocument(other)
        savedDocument.type("// saved\n")
        assertIs<SaveResult.Saved>(workspace.documents.save(saved))
        savedDocument.type("// typed after saving\n")
        val version = savedDocument.session.text.snapshot.value.version

        workspaces.watcher.report(saved)
        settle()
        assertEquals(version, savedDocument.session.text.snapshot.value.version)
        assertNull(savedDocument.conflict.value)

        Files.writeString(other, changed)
        workspaces.watcher.report(root, FileChangeKind.OVERFLOW)
        settle()
        assertEquals(changed, otherDocument.session.text.snapshot.value.text)
        assertNull(savedDocument.conflict.value)
    }

    @Test
    fun `changes in ignored directories trigger nothing`() = runTest(timeout = 60.seconds) {
        val path = file("ctx/robot.kmeta")
        val workspaces = TestWorkspaces(this)
        val workspace = workspaces.open(root, watch = true)
        workspace.awaitAnalysis()
        settle()
        val generation = workspace.analysis.value.generation
        val ignored = file("build/robot.kmeta")

        workspaces.watcher.report(ignored, FileChangeKind.CREATED)
        settle()

        assertEquals(generation, workspace.analysis.value.generation, "no rescan, no analysis")
        assertNull(workspace.files.value.find(ignored))
        assertNotNull(workspace.files.value.find(path))
    }

    @Test
    fun `refresh without watching checks open documents against the disk`() = runTest(timeout = 60.seconds) {
        val path = file("ctx/robot.kmeta")
        val workspace = TestWorkspaces(this).open(root)
        val document = workspace.openDocument(path)
        settle()

        Files.writeString(path, changed)
        settle()
        assertEquals(original, document.session.text.snapshot.value.text, "nothing watches")

        workspace.refresh()
        assertEquals(changed, document.session.text.snapshot.value.text)
        assertFalse(document.isDirty.value)
    }

    /** Types [text] at the start of the document. */
    private fun OpenDocument.type(text: String) {
        session.text.apply(listOf(TextEdit.insert(0, text)), EditOrigin.Typing)
    }
}
