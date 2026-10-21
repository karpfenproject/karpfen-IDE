package io.karpfen.ide.core.workspace.files

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * What the keys of one watch service watch, for [WatchServiceFileWatcher]. It follows symbolic links like the scan
 * ([WorkspaceScanner]), so every file the tree lists is watched under the path the workspace uses for it:
 * - the directories of the tree, by their paths in the tree: a symbolic link to a directory (also the root itself) is
 *   followed, the directories below it keep the link's path, and a directory reached a second time (another link to
 *   it, a link cycle) is not entered again, as the scan lists it empty;
 * - the directories holding the targets of symbolic links to files, wherever they lie: a change of such a target is
 *   reported as a change of the link.
 *
 * Confined to the watching coroutine.
 *
 * @param isIgnored true for paths that are not reported (and directories that are not entered).
 * @param register registers a directory with the watch service and returns its key.
 */
internal class WatchRegistry(
    private val isIgnored: (Path) -> Boolean,
    private val register: (Path) -> WatchKey,
) {

    /** What one key watches: the tree directory at [path] (null if none) and the links to files in it, by file name. */
    private class Watched(val realPath: Path) {
        var path: Path? = null
        val links = HashMap<Path, MutableSet<Path>>()
    }

    private val watched = HashMap<WatchKey, Watched>()
    private val keysByRealPath = HashMap<Path, WatchKey>()

    /** True while a directory of the tree is watched. */
    val watchesTree: Boolean get() = watched.values.any { it.path != null }

    /**
     * Registers [directory] and every non-ignored directory below it, following symbolic links, and the target
     * directories of the links to files among their files. Throws an [IOException] if a directory cannot be registered.
     */
    fun registerTree(directory: Path) {
        Files.walkFileTree(
            directory,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if ((dir == directory || !isIgnored(dir)) && watchTreeDirectory(dir)) {
                        FileVisitResult.CONTINUE
                    } else {
                        FileVisitResult.SKIP_SUBTREE
                    }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(file)) registerLinkTarget(file)
                    return FileVisitResult.CONTINUE
                }

                // A link cycle or a directory that cannot be listed: not watched, the walk goes on.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }

    /** The changes that [events] of [key] stand for; registers the directories and links they create. */
    fun changesOf(key: WatchKey, events: List<WatchEvent<*>>): List<FileChange> {
        val target = watched[key] ?: return emptyList()
        return events.flatMap { changesOf(target, it) }
    }

    /** Forgets [key], which became invalid, and returns the tree directory it watched (null if none). */
    fun cancel(key: WatchKey): Path? {
        val target = watched.remove(key) ?: return null
        keysByRealPath.remove(target.realPath, key)
        return target.path
    }

    private fun changesOf(target: Watched, event: WatchEvent<*>): List<FileChange> {
        if (event.kind() == OVERFLOW) {
            return (listOfNotNull(target.path) + target.links.values.flatten()).map { FileChange(it, FileChangeKind.OVERFLOW) }
        }
        val name = event.context() as Path
        val kind = when (event.kind()) {
            ENTRY_CREATE -> FileChangeKind.CREATED
            ENTRY_DELETE -> FileChangeKind.DELETED
            else -> FileChangeKind.MODIFIED
        }
        val inTree = target.path?.resolve(name)?.normalize()?.takeUnless(isIgnored)
        if (inTree != null && kind == FileChangeKind.CREATED) registerCreated(inTree)
        val links = target.links[name].orEmpty().filterNot(isIgnored)
        return (listOfNotNull(inTree) + links).map { FileChange(it, kind) }
    }

    private fun registerCreated(path: Path) {
        try {
            when {
                Files.isDirectory(path) -> registerTree(path)
                Files.isSymbolicLink(path) -> registerLinkTarget(path)
            }
        } catch (e: IOException) {
            // It vanished again; its deletion is reported by its parent.
            logger.debug("Cannot watch new {}: {}", path, e.toString())
        }
    }

    /** Watches [directory] as the tree directory at that path; false if its real directory is one under another path. */
    private fun watchTreeDirectory(directory: Path): Boolean {
        val target = watched.getValue(watch(directory))
        if (target.path != null && target.path != directory) return false
        target.path = directory
        return true
    }

    /** Watches the directory of the target of [link], so changes of the target are reported as changes of [link]. */
    private fun registerLinkTarget(link: Path) {
        try {
            val target = link.toRealPath()
            val directory = target.parent ?: return
            watched.getValue(watch(directory)).links.getOrPut(target.fileName) { HashSet() }.add(link)
        } catch (e: IOException) {
            logger.debug("Not watching the target of {}: {}", link, e.toString())
        }
    }

    /** The key watching the real directory of [directory], registering it under [directory] if there is none. */
    private fun watch(directory: Path): WatchKey {
        val realPath = realPathOf(directory)
        keysByRealPath[realPath]?.let { return it }
        val key = register(directory)
        watched.getOrPut(key) { Watched(realPath) }
        keysByRealPath[realPath] = key
        return key
    }

    private fun realPathOf(path: Path): Path =
        try {
            path.toRealPath()
        } catch (e: IOException) {
            path.toAbsolutePath().normalize()
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(WatchRegistry::class.java)
    }
}
