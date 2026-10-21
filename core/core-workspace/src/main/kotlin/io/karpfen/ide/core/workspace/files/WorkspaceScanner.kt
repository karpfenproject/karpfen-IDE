package io.karpfen.ide.core.workspace.files

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

/**
 * Builds the [WorkspaceTree] of a folder (spec §4): skips hidden directories (name starting with `.`) and ignored
 * directory names; sorts with [WorkspaceNode.ORDER]. Blocking; run on an IO dispatcher. An internal seam of the
 * workspace (injected through `WorkspaceCollaborators`), so coordinator tests can pass a fake tree.
 */
internal interface WorkspaceScanner {

    /** Scans [root] (an existing directory). */
    fun scan(root: Path): WorkspaceTree

    /** True if [path] lies in an ignored or hidden directory below [root]. */
    fun isIgnored(root: Path, path: Path): Boolean
}

/** The [WorkspaceScanner] walking the file system, ignoring hidden directories and [ignoredDirectoryNames]. */
internal fun WorkspaceScanner(ignoredDirectoryNames: Set<String>): WorkspaceScanner =
    FileSystemWorkspaceScanner(ignoredDirectoryNames)

/**
 * Walks the file system depth-first. Rules:
 * - a directory is skipped if its name starts with `.` or is one of [ignoredDirectoryNames] (files are never skipped,
 *   spec §4 ignores directories only);
 * - symbolic links to directories are followed once: a directory whose real path was already visited (a link cycle) is
 *   listed empty;
 * - a directory that cannot be listed is listed empty and logged; unless it vanished during the scan (a rescan
 *   follows), it is marked `isReadable = false`, so the workspace reports it instead of passing over its files; the
 *   scan itself never throws.
 */
internal class FileSystemWorkspaceScanner(private val ignoredDirectoryNames: Set<String>) : WorkspaceScanner {

    override fun scan(root: Path): WorkspaceTree {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return WorkspaceTree(directory(normalizedRoot, visited = HashSet()))
    }

    override fun isIgnored(root: Path, path: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (normalized == normalizedRoot) return false
        if (!normalized.startsWith(normalizedRoot)) return true
        val segments = normalizedRoot.relativize(normalized).map { it.toString() }
        // Every parent segment is a directory; the last one only counts if it is a directory itself (a deleted path
        // cannot be checked and is treated as a file, which at worst triggers one rescan).
        return segments.dropLast(1).any(::isIgnoredDirectoryName) ||
            (isIgnoredDirectoryName(segments.last()) && Files.isDirectory(normalized))
    }

    private fun isIgnoredDirectoryName(name: String): Boolean = name.startsWith(".") || name in ignoredDirectoryNames

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

    private fun children(entries: List<Path>, visited: MutableSet<Path>): List<WorkspaceNode> =
        entries.mapNotNull { entry ->
            val name = entry.fileName.toString()
            when {
                Files.isDirectory(entry) -> if (isIgnoredDirectoryName(name)) null else directory(entry, visited)
                else -> WorkspaceNode.File(entry, name, FileKind.of(name))
            }
        }

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
