package io.karpfen.ide.core.workspace.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The one test of the real `WatchService` watcher (the work-package plan allows at most one): the JDK's macOS watch
 * service polls every few seconds, so it runs with generous timeouts on real time. Every other watch test uses a fake.
 */
@Tag("slow-tolerant")
class WatchServiceFileWatcherTest {

    @TempDir
    lateinit var dir: Path

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `changes in new sub-directories are reported and ignored directories are not`() = runBlocking {
        val root = dir.toAbsolutePath().normalize()
        Files.createDirectories(root.resolve("build"))
        val watcher = FileWatcher()
        val scanner = WorkspaceScanner(setOf("build"))
        val target = root.resolve("sub/robot.kmeta")

        val events = CopyOnWriteArrayList<FileChange>()
        val reported = async(Dispatchers.Default) {
            withTimeout(80.seconds) {
                watcher.watch(root) { scanner.isIgnored(root, it) }.onEach { events += it }.first { it.path == target }
            }
        }
        delay(500.milliseconds) // let the watcher register the existing directories
        Files.writeString(root.resolve("build/ignored.kmeta"), "ignored")
        Files.createDirectories(root.resolve("sub"))
        // The new directory is registered when its creation is reported; keep writing until the file event arrives.
        while (!reported.isCompleted) {
            Files.writeString(target, "type \"A\" \"a\" { }\n${System.nanoTime()}")
            delay(1.seconds)
        }

        val change = reported.await()
        assertEquals(target, change.path)
        assertTrue(events.none { it.path.startsWith(root.resolve("build")) }, "events below an ignored directory: $events")
    }
}
