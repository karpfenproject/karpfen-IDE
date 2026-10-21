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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The one test of the real `WatchService` watcher (the work-package plan allows at most one): the JDK's macOS watch
 * service polls every few seconds, so it runs with generous timeouts on real time. Every other watch test uses a fake
 * (`WatchRegistryTest` covers what is registered and how events map, with fake keys).
 */
@Tag("slow-tolerant")
class WatchServiceFileWatcherTest {

    @TempDir
    lateinit var dir: Path

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `changes in new sub-directories and behind symbolic links are reported and ignored directories are not`() = runBlocking {
        val base = dir.toRealPath()
        val real = Files.createDirectories(base.resolve("real"))
        Files.createDirectories(real.resolve("build"))
        val shared = Files.createDirectories(base.resolve("shared"))
        val outside = Files.writeString(Files.createDirectories(base.resolve("outside")).resolve("m.kstates"), "STATEMACHINE")
        // The folder is opened through a link, holds a linked directory and a linked file whose targets lie outside it.
        val root = Files.createSymbolicLink(base.resolve("project"), real)
        Files.createSymbolicLink(real.resolve("linked"), shared)
        Files.createSymbolicLink(real.resolve("m.kstates"), outside)
        val watcher = FileWatcher()
        val scanner = WorkspaceScanner(setOf("build"))
        val targets = setOf(root.resolve("sub/robot.kmeta"), root.resolve("linked/robot.kmeta"), root.resolve("m.kstates"))

        val events = CopyOnWriteArrayList<FileChange>()
        val reported = async(Dispatchers.Default) {
            withTimeout(80.seconds) {
                watcher.watch(root) { scanner.isIgnored(root, it) }
                    .onEach { events += it }
                    .first { targets.all { target -> events.any { it.path == target } } }
            }
        }
        delay(500.milliseconds) // let the watcher register the existing directories
        Files.writeString(root.resolve("build/ignored.kmeta"), "ignored")
        Files.createDirectories(root.resolve("sub"))
        // The new directory is registered when its creation is reported; keep writing until every file event arrived.
        while (!reported.isCompleted) {
            val text = "type \"A\" \"a\" { }\n${System.nanoTime()}"
            listOf(real.resolve("sub/robot.kmeta"), shared.resolve("robot.kmeta"), outside).forEach { Files.writeString(it, text) }
            delay(1.seconds)
        }

        reported.await()
        assertTrue(events.none { it.path.startsWith(root.resolve("build")) }, "events below an ignored directory: $events")
        assertTrue(events.all { it.path.startsWith(root) }, "every change is reported under the opened folder's path: $events")
    }
}
