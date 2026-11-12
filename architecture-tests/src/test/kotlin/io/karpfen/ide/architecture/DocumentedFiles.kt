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

import com.lemonappdev.konsist.api.Konsist
import java.io.File

/**
 * A text file of the repository.
 *
 * @property relativePath the path relative to the repository root, with '/' separators, as failure messages print it,
 *   for example `core/core-text/build.gradle.kts`.
 * @property text the content of the file.
 */
internal class RepositoryFile(val relativePath: String, val text: String)

/**
 * The files whose documentation the documentation style rules check. They are read from disk, because the project
 * scope of Konsist holds no Gradle scripts and no other text files.
 *
 * The walk skips build output ([BuildOutput]) and hidden directories, as Konsist does, so generated sources are not
 * part of it. A source package named `build` is guarded like any other. The checkouts of other repositories in git
 * submodules ([Submodules]) are not part of this code base and are skipped too ([OwnCode]).
 */
internal object DocumentedFiles {

    /**
     * Every Kotlin source file and Gradle script of the repository, build tooling included, ordered by their path.
     * These are the sources of all source sets of every module and of build-logic, test fixtures among them, the
     * precompiled script plugins of build-logic, and the settings and build scripts of the root, of build-logic and of
     * every module.
     */
    val all: List<RepositoryFile> by lazy { read(::isKotlinSource) }

    /**
     * The other text documents of the repository, ordered by their path. These are the markdown documents, the XML
     * resources of the source sets with the logging configurations among them, and the version catalog. Their whole
     * text is prose or configuration that a reader follows, so the rules on reference ids and on punctuation search
     * all of it.
     */
    val documents: List<RepositoryFile> by lazy { read(::isTextDocument) }

    /**
     * The paths of the markdown files of the repository, relative to its root and ordered. The walk is the one of
     * [all] and [documents]. Hidden directories hold the configuration of tools, not documentation of this code base,
     * so no hidden directory is searched for markdown.
     */
    val markdownPaths: List<String> by lazy { pathsOf(::isMarkdown) }

    /** The files below the repository root whose relative path [accepts], with their text. */
    private fun read(accepts: (String) -> Boolean): List<RepositoryFile> {
        val root = File(Konsist.projectRootPath)
        return pathsOf(accepts).map { path -> RepositoryFile(path, File(root, path).readText()) }
    }

    /**
     * The relative paths of the files below the repository root that [accepts], ordered. Only the directories that
     * [isReadDirectory] accepts are entered.
     */
    private fun pathsOf(accepts: (String) -> Boolean): List<String> {
        val root = File(Konsist.projectRootPath)
        return root.walkTopDown()
            .onEnter { dir -> dir == root || isReadDirectory(dir.relativeTo(root).invariantSeparatorsPath) }
            .filter { it.isFile }
            .map { file -> file.relativeTo(root).invariantSeparatorsPath }
            .filter(accepts)
            .sorted()
            .toList()
    }

    /** True for a directory that is read. It holds no build output, is no submodule checkout and is not hidden. */
    private fun isReadDirectory(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/')
        return OwnCode.isOwnDirectory(relativePath) && !name.startsWith(".")
    }

    /** True for a Gradle script and for a Kotlin file of any kind inside a source directory. */
    private fun isKotlinSource(relativePath: String): Boolean {
        val isGradleScript = relativePath.endsWith(".gradle.kts")
        val isKotlinFile = relativePath.endsWith(".kt") || relativePath.endsWith(".kts")
        val isInSourceDirectory = "src" in relativePath.split('/').dropLast(1)
        return isGradleScript || (isKotlinFile && isInSourceDirectory)
    }

    /** True for the text documents of [documents]. */
    private fun isTextDocument(relativePath: String): Boolean {
        val segments = relativePath.split('/')
        return when {
            relativePath.endsWith(".md") -> true
            relativePath.endsWith(".xml") -> "src" in segments && "resources" in segments
            else -> segments.size == 2 && segments[0] == "gradle" && relativePath.endsWith(".toml")
        }
    }

    /** True for a markdown file, the files of [markdownPaths]. */
    private fun isMarkdown(relativePath: String): Boolean = relativePath.endsWith(".md")
}
