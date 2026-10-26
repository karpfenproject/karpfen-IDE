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
package io.karpfen.ide.architecture

/**
 * The git submodules of the repository: checkouts of other repositories, such as karpfen-dsl-tools, that the build uses
 * but that are not part of this code base. Their sources follow their own repository's rules, so the architecture and
 * documentation rules leave them out.
 *
 * The build script of the architecture tests reads the submodule directories from `.gitmodules` with build-logic's
 * `GitSubmodules`, the one reader of that file, leaves them out of the tests' inputs and hands the same list to the tests
 * in the system property [SYSTEM_PROPERTY], so adding a submodule needs no change here and the inputs and the tests
 * always leave out the same directories.
 */
internal object Submodules {

    /** The system property through which the test task hands over the submodule directories, comma-separated. */
    private const val SYSTEM_PROPERTY = "karpfen.submodules"

    /**
     * The submodule directories relative to the repository root, with '/' separators (e.g. `karpfen-dsl-tools`).
     *
     * @throws IllegalStateException if the tests run without the system property, that is not through the build.
     */
    val paths: List<String> by lazy {
        val value = checkNotNull(System.getProperty(SYSTEM_PROPERTY)) {
            "the system property $SYSTEM_PROPERTY is missing; run the architecture tests through the build"
        }
        value.split(',').filter { it.isNotEmpty() }
    }

    /**
     * True if [relativePath], relative to the repository root with '/' separators and with or without a leading '/',
     * is a submodule directory or lies inside one.
     */
    fun contains(relativePath: String): Boolean {
        val path = relativePath.trimStart('/')
        return paths.any { submodule -> path == submodule || path.startsWith("$submodule/") }
    }
}
