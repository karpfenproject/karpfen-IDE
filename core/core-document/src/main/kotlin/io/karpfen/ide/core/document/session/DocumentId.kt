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
package io.karpfen.ide.core.document.session

import java.nio.file.Path

/**
 * Identity of an open document: its absolute, normalized file path. Two ids are equal iff their paths are equal (no
 * symlink resolution).
 *
 * @property path the absolute, normalized path of the document's file.
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
