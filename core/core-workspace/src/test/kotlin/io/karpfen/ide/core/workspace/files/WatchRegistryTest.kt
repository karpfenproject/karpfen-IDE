package io.karpfen.ide.core.workspace.files

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.Watchable
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the watcher registers and how it maps events, on real trees with symbolic links and fake keys (the real watch
 * service is too slow on macOS for more than one test): every directory the scan lists is watched under its path in
 * the tree, and changes below links are reported under the links' paths.
 */
class WatchRegistryTest {

    @TempDir
    lateinit var dir: Path

    private val base: Path get() = dir.toRealPath()

    /** A key per registration; [directory] is the path it was registered under. */
    private class FakeKey(val directory: Path) : WatchKey {
        override fun isValid(): Boolean = true
        override fun pollEvents(): List<WatchEvent<*>> = emptyList()
        override fun reset(): Boolean = true
        override fun cancel() = Unit
        override fun watchable(): Watchable = directory
    }

    private class Event(private val kind: WatchEvent.Kind<Path>, private val name: String) : WatchEvent<Path> {
        override fun kind(): WatchEvent.Kind<Path> = kind
        override fun count(): Int = 1
        override fun context(): Path = Path.of(name)
    }

    private val keys = ArrayList<FakeKey>()

    private fun registry(root: Path, ignored: Set<String> = emptySet()): WatchRegistry {
        val scanner = WorkspaceScanner(ignored)
        return WatchRegistry({ scanner.isIgnored(root, it) }) { directory -> FakeKey(directory).also { keys += it } }
    }

    private fun registered(): List<Path> = keys.map { it.directory }

    private fun keyOf(directory: Path): FakeKey = keys.single { it.directory == directory }

    private fun write(path: Path, text: String = "x"): Path {
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    @Test
    fun `a root opened through a symbolic link is watched under the link's path`() {
        write(base.resolve("real/ctx/robot.kmeta"))
        val root = Files.createSymbolicLink(base.resolve("project"), base.resolve("real"))
        val registry = registry(root)

        registry.registerTree(root)

        assertEquals(setOf(root, root.resolve("ctx")), registered().toSet())
        assertTrue(registry.watchesTree)
        assertEquals(
            listOf(FileChange(root.resolve("ctx/robot.kmeta"), FileChangeKind.MODIFIED)),
            registry.changesOf(keyOf(root.resolve("ctx")), listOf(Event(ENTRY_MODIFY, "robot.kmeta"))),
        )
    }

    @Test
    fun `a linked directory is watched with its sub-directories under the link's path`() {
        val root = Files.createDirectories(base.resolve("root/plain")).parent
        write(base.resolve("shared/inner/robot.kmeta"))
        val linked = Files.createSymbolicLink(root.resolve("linked"), base.resolve("shared"))
        val registry = registry(root)

        registry.registerTree(root)

        assertEquals(setOf(root, root.resolve("plain"), linked, linked.resolve("inner")), registered().toSet())
        assertEquals(
            listOf(FileChange(linked.resolve("inner/robot.kmeta"), FileChangeKind.MODIFIED)),
            registry.changesOf(keyOf(linked.resolve("inner")), listOf(Event(ENTRY_MODIFY, "robot.kmeta"))),
        )
    }

    @Test
    fun `a directory reached twice is watched once under its first path, as the scan lists it`() {
        val root = Files.createDirectories(base.resolve("root/a/sub")).parent.parent
        Files.createSymbolicLink(root.resolve("b"), root.resolve("a"))
        Files.createSymbolicLink(root.resolve("a/loop"), root.resolve("a"))
        val registry = registry(root)

        registry.registerTree(root)

        val scanned = WorkspaceScanner(emptySet()).scan(root)
        val listed = listOf(root.resolve("a"), root.resolve("b")).filter {
            (scanned.find(it) as WorkspaceNode.Directory).children.isNotEmpty()
        }
        assertEquals(1, listed.size, "the scan lists one of them")
        assertEquals(setOf(root, listed.single(), listed.single().resolve("sub")), registered().toSet())
        assertEquals(registered().size, registered().toSet().size, "no directory is registered twice")
    }

    @Test
    fun `a change of a linked file's target is a change of the link, its neighbours are not reported`() {
        val root = Files.createDirectories(base.resolve("root"))
        val target = write(base.resolve("outside/robot.kmeta"))
        write(base.resolve("outside/other.kmeta"))
        val link = Files.createSymbolicLink(root.resolve("robot.kmeta"), target)
        val registry = registry(root)

        registry.registerTree(root)

        assertEquals(setOf(root, base.resolve("outside")), registered().toSet())
        val outside = keyOf(base.resolve("outside"))
        assertEquals(
            listOf(FileChange(link, FileChangeKind.MODIFIED)),
            registry.changesOf(outside, listOf(Event(ENTRY_MODIFY, "robot.kmeta"), Event(ENTRY_MODIFY, "other.kmeta"))),
        )
        assertEquals(base.resolve("outside"), outside.directory)
        assertEquals(null, registry.cancel(outside), "not a directory of the tree")
        assertTrue(registry.watchesTree)
    }

    @Test
    fun `links and directories created later are watched like those found at the start`() {
        val root = Files.createDirectories(base.resolve("root"))
        write(base.resolve("shared/robot.kmeta"))
        val target = write(base.resolve("outside/m.kstates"))
        val registry = registry(root)
        registry.registerTree(root)
        val linked = Files.createSymbolicLink(root.resolve("linked"), base.resolve("shared"))
        val link = Files.createSymbolicLink(root.resolve("m.kstates"), target)

        val created = registry.changesOf(keyOf(root), listOf(Event(ENTRY_CREATE, "linked"), Event(ENTRY_CREATE, "m.kstates")))

        assertEquals(listOf(FileChange(linked, FileChangeKind.CREATED), FileChange(link, FileChangeKind.CREATED)), created)
        assertEquals(setOf(root, linked, base.resolve("outside")), registered().toSet())
        assertEquals(
            listOf(FileChange(link, FileChangeKind.MODIFIED)),
            registry.changesOf(keyOf(base.resolve("outside")), listOf(Event(ENTRY_MODIFY, "m.kstates"))),
        )
    }

    @Test
    fun `a root that cannot be listed watches nothing, which the watcher reports instead of waiting forever`() {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "needs POSIX permissions")
        val root = Files.createDirectories(base.resolve("locked"))
        Files.setPosixFilePermissions(root, emptySet())
        try {
            assumeFalse(Files.isReadable(root), "the user can read any directory (root)")
            val registry = registry(root)

            registry.registerTree(root)

            assertFalse(registry.watchesTree)
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `ignored directories are not entered, also behind a link`() {
        val root = Files.createDirectories(base.resolve("root/build/out")).parent.parent
        Files.createDirectories(base.resolve("shared/x"))
        Files.createSymbolicLink(root.resolve(".hidden"), base.resolve("shared"))
        val registry = registry(root, ignored = setOf("build"))

        registry.registerTree(root)

        assertEquals(listOf(root), registered())
        assertEquals(emptyList(), registry.changesOf(keyOf(root), listOf(Event(ENTRY_CREATE, "build"), Event(ENTRY_MODIFY, ".hidden"))))
    }
}
