package io.karpfen.ide.ui.workbench.files.desktop

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.files.DeleteOutcome
import io.karpfen.ide.ui.workbench.testing.JdkLikeDesktopActions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The desktop adapters: headless-safe in the test JVM, file operations on java.nio, platform actions through the seam. */
class DesktopFileOperationsTest {

    @TempDir
    lateinit var dir: Path

    /** Records the platform actions and supports all of them. */
    private class RecordingDesktop(private val trashWorks: Boolean = true) : DesktopActions {
        val calls = mutableListOf<Pair<DesktopAction, Path>>()

        override fun supports(action: DesktopAction): Boolean = true

        override fun moveToTrash(path: Path): Boolean {
            calls.add(DesktopAction.MOVE_TO_TRASH to path)
            if (trashWorks) Files.delete(path)
            return trashWorks
        }

        override fun reveal(path: Path) {
            calls.add(DesktopAction.REVEAL to path)
        }

        override fun open(path: Path) {
            calls.add(DesktopAction.OPEN to path)
        }
    }

    @Test
    fun `in the headless test JVM nothing is supported, platform calls are no-ops and nothing throws`() = runTest {
        assertTrue(GraphicsEnvironment.isHeadless(), "the test task runs headless")
        val operations = desktopProjectFileOperations()
        assertFalse(operations.supportsTrash)
        assertFalse(operations.supportsRevealInFileManager)
        assertFalse(operations.supportsOpenExternally)
        val file = Files.writeString(dir.resolve("a.kmeta"), "x")
        operations.revealInFileManager(file)
        operations.openExternally(file)
        assertEquals(DeleteOutcome.DELETED, operations.delete(file), "deleted permanently without a trash")
        assertFalse(Files.exists(file))
        assertNull(nativeFolderChooser().chooseFolder("Open Folder", dir))
        assertNull(AwtFolderChooser(HostPlatform.MAC_OS).chooseFolder("Open Folder", null))
    }

    @Test
    fun `create, rename and delete work on the file system and refuse what they must`() {
        val operations = DesktopProjectFileOperations(AwtDesktopActions())
        val created = operations.createFile(dir, "robot.kmeta", "type \"A\" \"a\" { }\n")
        assertEquals(dir.resolve("robot.kmeta"), created)
        assertEquals("type \"A\" \"a\" { }\n", Files.readString(created))
        assertFailsWith<FileAlreadyExistsException> { operations.createFile(dir, "robot.kmeta", "") }
        listOf("../escape.kmeta", "a/b.kmeta", "a\\b", "..", ".", " ").forEach { name ->
            assertFailsWith<IOException>(name) { operations.createFile(dir, name, "") }
            assertFailsWith<IOException>(name) { operations.rename(created, name) }
        }

        val renamed = operations.rename(created, "room.kmeta")
        assertEquals(dir.resolve("room.kmeta"), renamed)
        Files.writeString(dir.resolve("taken.kmeta"), "")
        assertFailsWith<FileAlreadyExistsException> { operations.rename(renamed, "taken.kmeta") }

        val folder = Files.createDirectories(dir.resolve("folder/sub"))
        Files.writeString(folder.resolve("x.kstates"), "")
        assertEquals(DeleteOutcome.DELETED, operations.delete(dir.resolve("folder")))
        assertFalse(Files.exists(dir.resolve("folder")))
        assertFailsWith<NoSuchFileException> { operations.delete(dir.resolve("folder")) }
    }

    @Test
    fun `a rename that only changes the case renames on case-insensitive file systems too`() {
        val operations = DesktopProjectFileOperations(AwtDesktopActions())
        val file = Files.writeString(dir.resolve("Foo.kmeta"), "type \"A\" \"a\" { }\n")
        val folder = Files.createDirectories(dir.resolve("Robots"))
        Files.writeString(folder.resolve("x.kstates"), "x")

        val renamedFile = operations.rename(file, "foo.kmeta")
        val renamedFolder = operations.rename(folder, "robots")

        assertEquals(dir.resolve("foo.kmeta"), renamedFile)
        assertEquals(dir.resolve("robots"), renamedFolder)
        assertEquals(listOf("foo.kmeta", "robots"), names(dir), "the names on disk changed (macOS and Windows ignore case)")
        assertEquals("type \"A\" \"a\" { }\n", Files.readString(renamedFile))
        assertEquals(listOf("x.kstates"), names(renamedFolder))
    }

    @Test
    fun `a case-only rename onto another file of that name fails and changes nothing`() {
        val operations = DesktopProjectFileOperations(AwtDesktopActions())
        val upper = Files.writeString(dir.resolve("Foo.kmeta"), "upper")
        val lower = dir.resolve("foo.kmeta")
        assumeTrue(!Files.exists(lower), "two names differing in case are one file here (case-insensitive)")
        Files.writeString(lower, "lower")

        assertFailsWith<FileAlreadyExistsException> { operations.rename(upper, "foo.kmeta") }
        assertEquals("upper", Files.readString(upper))
        assertEquals("lower", Files.readString(lower))
    }

    private fun names(directory: Path): List<String> =
        Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `platform actions go through the desktop seam`() {
        val desktop = RecordingDesktop()
        val operations = DesktopProjectFileOperations(desktop)
        val file = Files.writeString(dir.resolve("a.kenv.json"), "{}")
        operations.openExternally(file)
        operations.revealInFileManager(file)
        assertEquals(DeleteOutcome.MOVED_TO_TRASH, operations.delete(file))
        assertEquals(listOf(DesktopAction.OPEN to file, DesktopAction.REVEAL to file, DesktopAction.MOVE_TO_TRASH to file), desktop.calls)

        val refused = Files.writeString(dir.resolve("b.kmeta"), "")
        assertFailsWith<IOException> { DesktopProjectFileOperations(RecordingDesktop(trashWorks = false)).delete(refused) }
        assertTrue(Files.exists(refused))
    }

    @Test
    fun `opening externally fails with an IOException whatever java awt Desktop throws, as the port promises`() {
        val desktop = JdkLikeDesktopActions()
        val operations = DesktopProjectFileOperations(desktop)
        val missing = dir.resolve("gone.kenv.json")

        val notThere = assertFailsWith<IOException> { operations.openExternally(missing) }
        assertTrue(notThere is NoSuchFileException, "$notThere")
        assertEquals("$missing: the file does not exist", notThere.message)

        val file = Files.writeString(dir.resolve("a.kenv.json"), "{}")
        operations.openExternally(file)
        assertEquals(listOf(file), desktop.opened)
        listOf(SecurityException("denied"), UnsupportedOperationException("no OPEN action")).forEach { failure ->
            desktop.failure = failure
            val refused = assertFailsWith<IOException> { operations.openExternally(file) }
            assertSame(failure, refused.cause)
        }
    }
}
