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
package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.DiagnosticCodes
import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.text.TextSnapshot
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the context tracker. Descriptors are loaded through the source loader with normalized text and loading
 * findings, and they are published with the contexts. An unreadable descriptor is an error that defines no context.
 * Syntax errors and loading findings belong to the descriptor's text, never to the resolution. An open descriptor is
 * read from its editor text, which the started tracker follows by itself, so unsaved edits change findings and
 * contexts, and closing it falls back to the disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextTrackerTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()
    private val scanner = WorkspaceScanner(WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES)

    /** A tracker loading through [loader] on the test's virtual time, with the open descriptors of [editor]. */
    private fun TestScope.tracker(
        loader: SourceLoader = SourceLoader(),
        editor: MutableStateFlow<Map<Path, TextSnapshot>> = MutableStateFlow(emptyMap()),
    ): ContextTracker {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ContextTracker(loader, EnvironmentDescriptorReader(), ContextResolver(), dispatcher, OpenDescriptors { editor })
    }

    @Test
    fun `descriptors are read from their normalized text, which is published with the contexts`() = runTest {
        val descriptor = DescriptorExamples.copyExplicitContext(root).toAbsolutePath().normalize()
        Files.writeString(descriptor, DescriptorExamples.text(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE).replace("\n", "\r\n"))
        val tracker = tracker()

        tracker.update(scanner.scan(root))

        val state = tracker.state.value
        assertSame(state.resolution, tracker.resolution.value)
        val text = assertNotNull(state.descriptors[descriptor]).snapshot.text
        assertTrue('\r' !in text, "normalized")
        val missing = state.resolution.diagnostics.getValue(descriptor).first { it.code == WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE }
        assertEquals("missing.kmodel", text.substring(missing.range.start, missing.range.end), "ranges refer to the normalized text")
    }

    @Test
    fun `loading findings and syntax errors belong to the descriptor's text, not to the resolution`() = runTest {
        val descriptor = DescriptorExamples.copyExplicitContext(root).toAbsolutePath().normalize()
        Files.writeString(descriptor, DescriptorExamples.text(DescriptorExamples.VALID).replaceFirst("\n", "\r\n"))
        val broken = root.resolve("sub/broken.kenv.json")
        Files.createDirectories(broken.parent)
        Files.writeString(broken, DescriptorExamples.text(DescriptorExamples.INVALID_JSON))
        val tracker = tracker()

        tracker.update(scanner.scan(root))

        val own = tracker.state.value.descriptors.mapValues { (_, source) -> source.ownDiagnostics.map { it.code } }
        val expected = mapOf(
            descriptor to listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS),
            broken to listOf(DiagnosticCodes.SYNTAX_ERROR),
        )
        assertEquals(expected, own)
        assertEquals(emptyMap(), tracker.resolution.value.diagnostics, "the descriptors have no project diagnostics")
        assertEquals(1, tracker.resolution.value.contexts.size)
    }

    @Test
    fun `an unreadable descriptor is an error and defines no context`() = runTest {
        val descriptor = DescriptorExamples.copyExplicitContext(root).toAbsolutePath().normalize()
        val tracker = tracker(SourceLoader { path -> if (path == descriptor) throw IOException("locked") else SourceLoader().load(path) })

        tracker.update(scanner.scan(root))

        val error = tracker.resolution.value.diagnostics.getValue(descriptor).single()
        assertEquals(WorkspaceDiagnosticCodes.UNREADABLE, error.code, "like every file the workspace cannot read")
        assertEquals(Severity.ERROR, error.severity)
        assertTrue("locked" in error.message)
        assertEquals(emptyList(), tracker.resolution.value.contexts)
        assertEquals(emptyMap(), tracker.state.value.descriptors)
        assertEquals(4, tracker.resolution.value.looseFiles.size)
    }

    @Test
    fun `once started, it follows an open descriptor's editor text by itself, and its closing falls back to the disk`() = runTest {
        val descriptor = DescriptorExamples.copyExplicitContext(root).toAbsolutePath().normalize()
        val editor = MutableStateFlow<Map<Path, TextSnapshot>>(emptyMap())
        var loads = 0
        val loader = SourceLoader { path ->
            loads++
            SourceLoader().load(path)
        }
        val tracker = tracker(loader, editor)
        tracker.start(backgroundScope)
        tracker.update(scanner.scan(root))
        assertEquals(null, tracker.resolution.value.diagnostics[descriptor])
        val loadsAfterScan = loads

        val edited = TextSnapshot(Files.readString(descriptor).replace("cleaning_robot.kmodel", "missing.kmodel"), 3)
        editor.value = mapOf(descriptor to edited)
        runCurrent()

        val missing = tracker.resolution.value.diagnostics.getValue(descriptor).single()
        assertEquals(WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE, missing.code)
        assertEquals("missing.kmodel", edited.text.substring(missing.range.start, missing.range.end))
        assertSame(edited, tracker.state.value.descriptors[descriptor]?.snapshot, "findings refer to the editor's snapshot")
        val context = tracker.resolution.value.contexts.single()
        assertEquals(emptyList(), context.models, "the context follows the edit and leaves out the missing model")

        editor.value = emptyMap()
        runCurrent()

        assertEquals(null, tracker.resolution.value.diagnostics[descriptor], "closed without saving: the disk counts again")
        assertEquals(Files.readString(descriptor), tracker.state.value.descriptors.getValue(descriptor).snapshot.text)
        assertEquals(loadsAfterScan + 1, loads, "only the descriptor that was open is loaded again")
    }
}
