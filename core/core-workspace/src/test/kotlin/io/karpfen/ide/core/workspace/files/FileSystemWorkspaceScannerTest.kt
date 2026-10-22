package io.karpfen.ide.core.workspace.files

import io.karpfen.ide.core.workspace.WorkspaceOptions
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the file-system scanner: the tree order (directories first, case-insensitive names), the ignore rules for
 * directories, symbolic links and cycles, and directories that cannot be listed.
 */
class FileSystemWorkspaceScannerTest {

    @TempDir
    lateinit var dir: Path

    private val scanner = WorkspaceScanner(WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES)

    /** Writes [text] to [relative] below the test directory, creating its directories. */
    private fun write(relative: String, text: String = "") {
        val path = dir.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    @Test
    fun `directories come first, then files, each in case-insensitive name order`() {
        listOf("b.kmeta", "A.kmodel", "c.txt", "Zeta/x.kstates", "alpha/y.kmeta", "alpha/Beta/z.kmeta", "B.kstates").forEach(::write)

        val tree = scanner.scan(dir)

        assertEquals(listOf("alpha", "Zeta", "A.kmodel", "b.kmeta", "B.kstates", "c.txt"), tree.root.children.map { it.name })
        val alpha = assertIs<WorkspaceNode.Directory>(tree.root.children.first())
        assertEquals(listOf("Beta", "y.kmeta"), alpha.children.map { it.name })
        assertEquals(
            listOf("alpha/Beta/z.kmeta", "alpha/y.kmeta", "Zeta/x.kstates", "A.kmodel", "b.kmeta", "B.kstates", "c.txt"),
            tree.files().map { dir.toAbsolutePath().normalize().relativize(it.path).toString().replace('\\', '/') },
        )
    }

    @Test
    fun `ignored and hidden directories are skipped, hidden files are listed`() {
        WorkspaceOptions.DEFAULT_IGNORED_DIRECTORIES.forEach { write("$it/skipped.kmeta") }
        write(".hidden/skipped.kmeta")
        write("nested/build/skipped.kmeta")
        write("nested/kept.kmeta")
        write(".notes")

        val tree = scanner.scan(dir)

        assertEquals(setOf("kept.kmeta", ".notes"), tree.files().map { it.name }.toSet())
        assertTrue(tree.files().none { it.name == "skipped.kmeta" })
    }

    @Test
    fun `nodes carry absolute normalized paths and file kinds`() {
        write("ctx/EVENTS.kmeta")
        write("ctx/robot.kenv.json")
        write("ctx/robot.kstates.layout.json")

        val tree = scanner.scan(dir.resolve("ctx/.."))

        assertEquals(dir.toAbsolutePath().normalize(), tree.root.path)
        assertEquals(
            mapOf("EVENTS.kmeta" to FileKind.EVENTS_KMETA, "robot.kenv.json" to FileKind.ENV_DESCRIPTOR, "robot.kstates.layout.json" to FileKind.LAYOUT),
            tree.files().associate { it.name to it.kind },
        )
        tree.files().forEach { assertEquals(it.path.toAbsolutePath().normalize(), it.path) }
        assertIs<WorkspaceNode.File>(tree.find(dir.resolve("ctx/EVENTS.kmeta").toAbsolutePath().normalize()))
    }

    @Test
    fun `an empty folder is an empty tree`() {
        val tree = scanner.scan(dir)

        assertEquals(emptyList(), tree.root.children)
    }

    @Test
    fun `a symbolic link cycle does not recurse forever`() {
        write("a/model.kmodel")
        Files.createSymbolicLink(dir.resolve("a/loop"), dir.resolve("a"))

        val tree = scanner.scan(dir)

        val loop = assertIs<WorkspaceNode.Directory>(tree.find(dir.resolve("a/loop").toAbsolutePath().normalize()))
        assertEquals(emptyList(), loop.children)
    }

    @Test
    fun `a directory that cannot be listed is marked unreadable, a readable one and a link cycle are not`() {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "needs POSIX permissions")
        write("locked/inner.kmeta")
        write("open/model.kmodel")
        val locked = dir.resolve("locked")
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            assumeFalse(Files.isReadable(locked), "the user can read any directory (root)")

            val tree = scanner.scan(dir)

            val unreadable = assertIs<WorkspaceNode.Directory>(tree.find(locked.toAbsolutePath().normalize()))
            assertFalse(unreadable.isReadable)
            assertEquals(emptyList(), unreadable.children)
            assertTrue(tree.root.isReadable)
            assertTrue(assertIs<WorkspaceNode.Directory>(tree.find(dir.resolve("open").toAbsolutePath().normalize())).isReadable)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `isIgnored covers ignored and hidden directories and paths outside the root`() {
        Files.createDirectories(dir.resolve("build"))
        Files.createDirectories(dir.resolve("src/.cache"))
        write(".hidden-file")
        val root = dir.toAbsolutePath().normalize()

        assertTrue(scanner.isIgnored(root, root.resolve("build")), "an ignored directory itself")
        assertTrue(scanner.isIgnored(root, root.resolve("build/out.kmeta")), "below an ignored directory")
        assertTrue(scanner.isIgnored(root, root.resolve("node_modules/x/y.kmeta")), "below a missing ignored directory")
        assertTrue(scanner.isIgnored(root, root.resolve("src/.cache")), "a hidden directory")
        assertTrue(scanner.isIgnored(root, root.resolve("src/.cache/a.kmeta")), "below a hidden directory")
        assertTrue(scanner.isIgnored(root, root.resolve("../elsewhere.kmeta")), "outside the root")
        assertFalse(scanner.isIgnored(root, root), "the root itself")
        assertFalse(scanner.isIgnored(root, root.resolve("src/a.kmeta")))
        assertFalse(scanner.isIgnored(root, root.resolve(".hidden-file")), "hidden files are not ignored")
        assertFalse(scanner.isIgnored(root, root.resolve("gone.kmeta")), "a deleted file")
    }
}
