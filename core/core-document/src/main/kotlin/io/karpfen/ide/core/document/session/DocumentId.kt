package io.karpfen.ide.core.document.session

import java.nio.file.Path

/**
 * Identity of an open document: its absolute, normalized file path (spec §3.4). Two ids are equal iff their paths
 * are equal (no symlink resolution).
 */
public data class DocumentId(public val path: Path) {
    init {
        require(path.isAbsolute) { "document paths are absolute, was $path" }
        require(path.normalize() == path) { "document paths are normalized, was $path" }
    }

    /** The file name, e.g. `cleaning_robot.kstates`. */
    public val fileName: String get() = path.fileName?.toString() ?: path.toString()

    override fun toString(): String = path.toString()

    public companion object {
        /** The id of [path], made absolute against the working directory and normalized. */
        public fun of(path: Path): DocumentId = DocumentId(path.toAbsolutePath().normalize())
    }
}
