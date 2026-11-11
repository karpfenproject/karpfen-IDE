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
 * How a descriptor names files. It uses paths relative to its own directory, with `/` as the separator on every
 * platform. This single rule serves the contexts the workspace resolves, the paths that completion offers and that
 * hover and navigation follow, and the paths that the file templates write.
 */
public object DescriptorPaths {

    /**
     * The file that [value] names in a descriptor in [directory], absolute and normalized. Subdirectories and `..` are
     * allowed. It is null if [value] is not a valid path on this platform.
     */
    public fun resolve(directory: Path, value: String): Path? =
        try {
            directory.resolve(value).toAbsolutePath().normalize()
        } catch (e: InvalidPathException) {
            null
        }

    /**
     * How a descriptor in [directory] names [file]. The path is relative and uses `/` separators. A file on another
     * file-system root, which `relativize` rejects, can only be named by its absolute path.
     */
    public fun relative(directory: Path, file: Path): String =
        try {
            directory.relativize(file).joinToString("/")
        } catch (e: IllegalArgumentException) {
            file.toString()
        }
}
