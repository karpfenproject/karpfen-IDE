package io.karpfen.ide.core.workspace.context

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.core.document.fixtures.DescriptorExamples
import io.karpfen.ide.core.workspace.WorkspaceOptions
import io.karpfen.ide.core.workspace.diagnostics.WorkspaceDiagnosticCodes
import io.karpfen.ide.core.workspace.document.SourceLoader
import io.karpfen.ide.core.workspace.environment.EnvironmentDescriptorReader
import io.karpfen.ide.core.workspace.files.WorkspaceScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Covers the context tracker: descriptors are loaded through the source loader (normalized text, loading findings),
 * published with the contexts, and an unreadable descriptor is an error that defines no context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextTrackerTest {

    @TempDir
    lateinit var dir: Path

    private val root: Path get() = dir.toAbsolutePath().normalize()
    private val scanner = WorkspaceScanner(WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES)

    /** A tracker loading through [loader] on the test's virtual time. */
    private fun TestScope.tracker(loader: SourceLoader = SourceLoader()) =
        ContextTracker(loader, EnvironmentDescriptorReader(), ContextResolver(), StandardTestDispatcher(testScheduler))

    @Test
    fun `descriptors are read from their normalized text, which is published with the contexts`() = runTest {
        val descriptor = DescriptorExamples.copyExplicitContext(root).toAbsolutePath().normalize()
        Files.writeString(descriptor, DescriptorExamples.text(DescriptorExamples.UNKNOWN_KEY_AND_MISSING_FILE).replace("\n", "\r\n"))
        val tracker = tracker()

        tracker.update(scanner.scan(root))

        val state = tracker.state.value
        assertSame(state.resolution, tracker.resolution.value)
        val text = assertNotNull(state.descriptorTexts[descriptor]).text
        assertTrue('\r' !in text, "normalized")
        val missing = state.resolution.diagnostics.getValue(descriptor).first { it.code == WorkspaceDiagnosticCodes.DESCRIPTOR_MISSING_FILE }
        assertEquals("missing.kmodel", text.substring(missing.range.start, missing.range.end), "ranges refer to the normalized text")
    }

    @Test
    fun `loading findings of a descriptor are reported on it`() = runTest {
        val descriptor = DescriptorExamples.copyExplicitContext(root).toAbsolutePath().normalize()
        Files.writeString(descriptor, DescriptorExamples.text(DescriptorExamples.VALID).replaceFirst("\n", "\r\n"))
        val tracker = tracker()

        tracker.update(scanner.scan(root))

        assertEquals(
            listOf(WorkspaceDiagnosticCodes.MIXED_LINE_SEPARATORS),
            tracker.resolution.value.diagnostics.getValue(descriptor).map { it.code },
        )
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
        assertEquals(emptyMap(), tracker.state.value.descriptorTexts)
        assertEquals(4, tracker.resolution.value.looseFiles.size)
    }
}
