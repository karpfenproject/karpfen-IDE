package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File

/**
 * A text file of the repository.
 *
 * @property relativePath the path relative to the repository root, with '/' separators, as failure messages print it
 *   (e.g. `core/core-text/build.gradle.kts`).
 * @property text the content of the file.
 */
internal class RepositoryFile(val relativePath: String, val text: String)

/**
 * The files whose documentation the documentation style rules check: every Kotlin source file and Gradle script of the
 * repository, build tooling included. These are the sources of all source sets of every module and of build-logic,
 * test fixtures among them, the precompiled script plugins of build-logic, and the settings and build scripts of the
 * root, of build-logic and of every module.
 *
 * Konsist's project scope holds no Gradle scripts, so the files are read from disk. Hidden directories and build
 * output are skipped, as Konsist skips them, so generated sources are not part of it.
 */
internal object DocumentedFiles {

    /** The documented files, ordered by their path. */
    val all: List<RepositoryFile> by lazy {
        val root = File(Konsist.projectRootPath)
        root.walkTopDown()
            .onEnter { dir -> dir == root || !(dir.name.startsWith(".") || dir.name == "build") }
            .filter { it.isFile }
            .map { file -> file to file.relativeTo(root).invariantSeparatorsPath }
            .filter { (_, path) -> isDocumented(path) }
            .map { (file, path) -> RepositoryFile(path, file.readText()) }
            .sortedBy { it.relativePath }
            .toList()
    }

    /** True for a Gradle script anywhere and for a Kotlin file of any kind inside a source directory. */
    private fun isDocumented(relativePath: String): Boolean {
        val isGradleScript = relativePath.endsWith(".gradle.kts")
        val isKotlinFile = relativePath.endsWith(".kt") || relativePath.endsWith(".kts")
        return isGradleScript || isKotlinFile && "src" in relativePath.split('/').dropLast(1)
    }
}
