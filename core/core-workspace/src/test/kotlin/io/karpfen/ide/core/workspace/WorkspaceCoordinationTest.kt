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

import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.text.EditOrigin
import io.karpfen.ide.core.text.TextEdit
import io.karpfen.ide.core.workspace.analysis.AnalysisSources
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.analysis.ProjectAnalyzer
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.context.ContextResolver
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.document.TextFileStore
import io.karpfen.ide.core.workspace.document.WorkspaceDocuments
import io.karpfen.ide.core.workspace.document.documentManager
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.FileChangeKind
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers the [DefaultWorkspace] coordinator with fake collaborators injected through [WorkspaceCollaborators]: the tree
 * and contexts from the injected scanner and resolver, the routing of debounced watch batches and overflows to the
 * open documents, the analysis triggers and their debounce, and closing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCoordinationTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()

    /** Always answers [tree]; ignores everything below `ignored/`. */
    private class FakeScanner(var tree: WorkspaceTree) : WorkspaceScanner {
        var scans = 0

        override fun scan(root: Path): WorkspaceTree = tree.also { scans++ }

        override fun isIgnored(root: Path, path: Path): Boolean = path.startsWith(root.resolve("ignored"))
    }

    /** Real documents that record the changes routed to them. */
    private class RecordingDocuments(private val delegate: WorkspaceDocuments) : WorkspaceDocuments by delegate {
        val changes = ArrayList<Pair<Path, Boolean>>()
        var closedAll = false

        override suspend fun onFileChanged(path: Path, deleted: Boolean) {
            changes += path to deleted
            delegate.onFileChanged(path, deleted)
        }

        override fun closeAll() {
            closedAll = true
            delegate.closeAll()
        }
    }

    /** Counts runs; analyses nothing. */
    private class CountingAnalyzer : ProjectAnalyzer {
        var runs = 0

        override suspend fun analyze(resolution: ContextResolution, sources: AnalysisSources, generation: Long): ProjectAnalysis {
            runs++
            return ProjectAnalysis(generation, emptyList(), resolution.diagnostics)
        }
    }

    private lateinit var scanner: FakeScanner
    private lateinit var documents: RecordingDocuments
    private lateinit var analyzer: CountingAnalyzer
    private lateinit var watcher: FakeFileWatcher

    /** Writes a small metamodel to [relative] below the root, creating its directories. */
    private fun file(relative: String): Path =
        root.resolve(relative).also { Files.createDirectories(it.parent); Files.writeString(it, "type \"A\" \"a\" {\n}\n") }

    /** A coordinator over fresh fakes on the test's virtual time, watching through [watcher]. */
    private fun TestScope.workspace(): DefaultWorkspace {
        val workspaces = TestWorkspaces(this)
        scanner = FakeScanner(WorkspaceTree(WorkspaceNode.Directory(root, "root", listOf(WorkspaceNode.File(root.resolve("x.kmeta"), "x.kmeta", FileKind.KMETA)))))
        documents = RecordingDocuments(documentManager(this))
        analyzer = CountingAnalyzer()
        watcher = workspaces.watcher
        val collaborators = WorkspaceCollaborators(
            TextFileStore(), watcher, SourceLoader(), EnvironmentDescriptorReader(), ContextResolver(), analyzer, documents, scanner,
        )
        return DefaultWorkspace(root, backgroundScope, workspaces.options(watch = true), defaultLanguageRegistry(), collaborators)
    }

    /** Lets the coordinator start and run its first scan. */
    private fun TestScope.awaitFirstScan() = runCurrent()

    @Test
    fun `the tree and the contexts come from the injected scanner and resolver`() = runTest {
        val workspace = workspace()
        runCurrent()

        assertSame(scanner.tree, workspace.files.value)
        assertEquals(listOf(root.resolve("x.kmeta")), workspace.contexts.value.contexts.single().files)
        assertSame(documents, workspace.documents)
    }

    @Test
    fun `a debounced batch rescans once and reaches the open documents at or below its paths`() = runTest {
        val workspace = workspace()
        awaitFirstScan()
        val inDirectory = file("a/x.kmeta")
        val deleted = file("b/y.kmeta")
        val untouched = file("c/z.kmeta")
        listOf(inDirectory, deleted, untouched).forEach { documents.open(it) }
        runCurrent()
        val scans = scanner.scans

        watcher.report(root.resolve("a"))
        advanceTimeBy(150.milliseconds)
        watcher.report(deleted, FileChangeKind.DELETED)
        watcher.report(root.resolve("ignored/w.kmeta"))
        advanceTimeBy(199.milliseconds)
        runCurrent()
        assertEquals(scans, scanner.scans)
        assertEquals(emptyList(), documents.changes)

        advanceTimeBy(2.milliseconds)
        runCurrent()
        assertEquals(scans + 1, scanner.scans)
        assertEquals(listOf(inDirectory to false, deleted to true), documents.changes)
        assertTrue(workspace.isAnalyzing.value, "the rescan scheduled an analysis")
    }

    @Test
    fun `an overflow reaches every open document`() = runTest {
        workspace()
        awaitFirstScan()
        val paths = listOf(file("a.kmeta"), file("b/c.kmeta"))
        paths.forEach { documents.open(it) }
        runCurrent()

        watcher.report(root, FileChangeKind.OVERFLOW)
        settle()

        assertEquals(paths.map { it to false }, documents.changes)
    }

    @Test
    fun `rescans and edits of open documents trigger one analysis per quiet period`() = runTest {
        val workspace = workspace()
        settle()
        assertEquals(1, analyzer.runs, "the initial analysis")

        workspace.refresh()
        settle()
        assertEquals(2, analyzer.runs, "after a rescan")

        val path = file("a.kmeta")
        documents.open(path)
        settle()
        assertEquals(3, analyzer.runs, "after opening")
        val text = assertNotNull(documents.get(path)).session.text
        repeat(5) {
            text.apply(listOf(TextEdit.insert(0, "x")), EditOrigin.Typing)
            advanceTimeBy(100.milliseconds)
        }
        runCurrent()
        assertEquals(3, analyzer.runs, "typing every 100 ms keeps postponing the analysis")
        advanceTimeBy(201.milliseconds)
        runCurrent()
        assertEquals(4, analyzer.runs)
        assertFalse(workspace.isAnalyzing.value)
        assertEquals(4, workspace.analysis.value.generation)
    }

    @Test
    fun `closing closes the documents, stops watching and is idempotent`() = runTest {
        val workspace = workspace()
        settle()
        val scans = scanner.scans

        workspace.close()
        workspace.close()
        watcher.report(root.resolve("x.kmeta"))
        settle()

        assertTrue(documents.closedAll)
        assertEquals(scans, scanner.scans)
        assertFalse(workspace.isAnalyzing.value)
    }
}
