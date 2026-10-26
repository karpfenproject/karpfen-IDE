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
package io.karpfen.ide.core.workspace.environment

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * How a descriptor names files: by paths relative to its own directory, with `/` as separator on every platform. The
 * one rule for the contexts the workspace resolves, for the paths completion offers and hover and navigation follow, and
 * for the paths the file templates write.
 */
public object DescriptorPaths {

    /**
     * The file [value] names in a descriptor in [directory]: absolute and normalized (sub-directories and `..` are
     * allowed), or null if [value] is no valid path on this platform.
     */
    public fun resolve(directory: Path, value: String): Path? =
        try {
            directory.resolve(value).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            null
        }

    /**
     * How a descriptor in [directory] names [file]: relative, with `/` separators. A file on another file-system root,
     * which `relativize` rejects, can only be named by its absolute path.
     */
    public fun relative(directory: Path, file: Path): String =
        try {
            directory.relativize(file).joinToString("/")
        } catch (e: IllegalArgumentException) {
            file.toString()
        }
}
