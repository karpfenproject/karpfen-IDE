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
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

/**
 * Builds the [WorkspaceTree] of a folder. It skips hidden directories, whose name starts with `.`, and ignored
 * directory names. It sorts with [WorkspaceNode.ORDER]. Scanning blocks, so run it on an IO dispatcher. It is an
 * internal seam of the workspace, injected through `WorkspaceCollaborators`, so coordinator tests can pass a fake tree.
 */
internal interface WorkspaceScanner {

    /** Scans [root], which is an existing directory. */
    fun scan(root: Path): WorkspaceTree

    /** True if [path] lies in an ignored or hidden directory below [root]. */
    fun isIgnored(root: Path, path: Path): Boolean
}

/** The [WorkspaceScanner] that walks the file system and ignores hidden directories and [ignoredDirectoryNames]. */
internal fun WorkspaceScanner(ignoredDirectoryNames: Set<String>): WorkspaceScanner =
    FileSystemWorkspaceScanner(ignoredDirectoryNames)

/**
 * Walks the file system depth-first by these rules.
 * - A directory is skipped if its name starts with `.` or is one of [ignoredDirectoryNames]. Files are never skipped.
 *   Only directories are ignored.
 * - Symbolic links to directories are followed once. A directory whose real path was already visited, as in a link
 *   cycle, is listed as empty.
 * - A directory that cannot be listed is listed as empty and logged. Unless it vanished during the scan, it is marked
 *   with `isReadable = false`, so the workspace reports it instead of passing over its files. For a vanished directory
 *   a rescan follows anyway. The scan itself never throws.
 */
internal class FileSystemWorkspaceScanner(private val ignoredDirectoryNames: Set<String>) : WorkspaceScanner {

    override fun scan(root: Path): WorkspaceTree {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return WorkspaceTree(directory(normalizedRoot, visited = HashSet()))
    }

    /**
     * True if [path] lies outside [root] or in an ignored directory below it. Every parent segment of [path] is a
     * directory. The last segment counts only if [path] is a directory itself. A deleted path cannot be checked and is
     * treated as a file, which at worst triggers one extra rescan.
     */
    override fun isIgnored(root: Path, path: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (normalized == normalizedRoot) return false
        if (!normalized.startsWith(normalizedRoot)) return true
        val segments = normalizedRoot.relativize(normalized).map { it.toString() }
        return segments.dropLast(1).any(::isIgnoredDirectoryName) ||
            (isIgnoredDirectoryName(segments.last()) && Files.isDirectory(normalized))
    }

    /** True for hidden directory names and the configured [ignoredDirectoryNames]. */
    private fun isIgnoredDirectoryName(name: String): Boolean = name.startsWith(".") || name in ignoredDirectoryNames

    /**
     * The directory node of [path]. [visited] holds the real paths listed so far, so a link cycle is listed as empty.
     */
    private fun directory(path: Path, visited: MutableSet<Path>): WorkspaceNode.Directory {
        val name = path.fileName?.toString() ?: path.toString()
        if (!visited.add(realPathOf(path))) return WorkspaceNode.Directory(path, name, emptyList())
        val entries = try {
            Files.newDirectoryStream(path).use { stream -> stream.toList() }
        } catch (e: IOException) {
            logger.warn("Cannot list directory {}: {}", path, e.toString())
            val vanished = e is NoSuchFileException || e is NotDirectoryException
            return WorkspaceNode.Directory(path, name, emptyList(), isReadable = vanished)
        }
        return WorkspaceNode.Directory(path, name, children(entries, visited).sortedWith(WorkspaceNode.ORDER))
    }

    /** The nodes of the [entries] of a directory, unsorted. Ignored directories are left out. */
    private fun children(entries: List<Path>, visited: MutableSet<Path>): List<WorkspaceNode> =
        entries.mapNotNull { entry ->
            val name = entry.fileName.toString()
            when {
                Files.isDirectory(entry) -> if (isIgnoredDirectoryName(name)) null else directory(entry, visited)
                else -> WorkspaceNode.File(entry, name, FileKind.of(name))
            }
        }

    /** The real path of [path], or [path] itself if it cannot be resolved. */
    private fun realPathOf(path: Path): Path =
        try {
            path.toRealPath()
        } catch (e: IOException) {
            path
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(FileSystemWorkspaceScanner::class.java)
    }
}
