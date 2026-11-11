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
 * Records what the keys of one watch service watch, for [WatchServiceFileWatcher]. It follows symbolic links the same
 * way the scan in [WorkspaceScanner] does. So every file the tree lists is watched under the path the workspace uses
 * for it. Two kinds of directories are watched.
 * - The directories of the tree, under their paths in the tree. A symbolic link to a directory is followed, and that
 *   includes the root itself. The directories below it keep the path of the link. A directory reached a second time,
 *   through another link to it or through a link cycle, is not entered again, just as the scan lists it as empty.
 * - The directories that hold the targets of symbolic links to files, wherever they lie. A change of such a target is
 *   reported as a change of the link.
 *
 * Only the watching coroutine uses an instance.
 *
 * @param isIgnored true for paths that are not reported and for directories that are not entered.
 * @param register registers a directory with the watch service and returns its key.
 */
internal class WatchRegistry(
    private val isIgnored: (Path) -> Boolean,
    private val register: (Path) -> WatchKey,
) {

    /**
     * What one key watches. [path] is the tree directory, or null if the key watches none. [links] holds the links to
     * files in the watched directory, by the file name of their target.
     */
    private class Watched(val realPath: Path) {
        var path: Path? = null
        val links = HashMap<Path, MutableSet<Path>>()
    }

    /** What each key watches. */
    private val watched = HashMap<WatchKey, Watched>()

    /** The key of each real directory, so a directory reached under two paths is registered only once. */
    private val keysByRealPath = HashMap<Path, WatchKey>()

    /** True while a directory of the tree is watched. */
    val watchesTree: Boolean get() = watched.values.any { it.path != null }

    /**
     * Registers [directory] and every directory below it that is not ignored, following symbolic links. It also
     * registers the target directories of the links to files among their files.
     *
     * @throws IOException if a directory cannot be registered.
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

                /** A link cycle or a directory that cannot be listed is not watched, and the walk goes on. */
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }

    /** The changes that [events] of [key] stand for. It registers the directories and links that the events create. */
    fun changesOf(key: WatchKey, events: List<WatchEvent<*>>): List<FileChange> {
        val target = watched[key] ?: return emptyList()
        return events.flatMap { changesOf(target, it) }
    }

    /** Forgets [key], which became invalid, and returns the tree directory it watched, or null if none. */
    fun cancel(key: WatchKey): Path? {
        val target = watched.remove(key) ?: return null
        keysByRealPath.remove(target.realPath, key)
        return target.path
    }

    /**
     * The changes that one [event] of the key watching [target] stands for. These are the tree path of the entry and
     * the links to it. An overflow stands for everything the key watches.
     */
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

    /**
     * Watches the created directory tree or link at [path]. A path that vanished again before it could be registered is
     * skipped, because the key of its parent reports the deletion.
     */
    private fun registerCreated(path: Path) {
        try {
            when {
                Files.isDirectory(path) -> registerTree(path)
                Files.isSymbolicLink(path) -> registerLinkTarget(path)
            }
        } catch (e: IOException) {
            logger.debug("Cannot watch new {}: {}", path, e.toString())
        }
    }

    /**
     * Watches [directory] as the tree directory at that path. It returns false if its real directory is already watched
     * under another path.
     */
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

    /** The key that watches the real directory of [directory]. If there is none, it registers one under [directory]. */
    private fun watch(directory: Path): WatchKey {
        val realPath = realPathOf(directory)
        keysByRealPath[realPath]?.let { return it }
        val key = register(directory)
        watched.getOrPut(key) { Watched(realPath) }
        keysByRealPath[realPath] = key
        return key
    }

    /** The real path of [path], or its normalized absolute path if it cannot be resolved. */
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
