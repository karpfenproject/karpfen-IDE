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
 * The files whose documentation the documentation style rules check, read from disk, because Konsist's project scope
 * holds no Gradle scripts and no other text files.
 *
 * Build output ([BuildOutput]) and hidden directories are skipped, as Konsist skips them, so generated sources are not
 * part of it, while a source package named `build` is guarded like any other. The CI workflows below `.github` are the
 * one hidden directory that is read, for the reference-id rule only. The checkouts of other repositories in git
 * submodules ([Submodules]) are not part of this code base and are skipped too.
 */
internal object DocumentedFiles {

    /**
     * Every Kotlin source file and Gradle script of the repository, build tooling included, ordered by their path:
     * the sources of all source sets of every module and of build-logic, test fixtures among them, the precompiled
     * script plugins of build-logic, and the settings and build scripts of the root, of build-logic and of every
     * module.
     */
    val all: List<RepositoryFile> by lazy { read(::isKotlinSource) }

    /**
     * The other text documents of the repository, ordered by their path: the markdown documents except the archived
     * records of phase 1 below `docs/history`, the CI workflows, the XML resources of the source sets (the logging
     * configurations among them) and the version catalog. Their whole text is prose or configuration a reader follows,
     * so the reference-id rule searches all of it.
     */
    val documents: List<RepositoryFile> by lazy { read(::isTextDocument) }

    /** The files below the repository root whose relative path [accepts], skipping build output and hidden directories. */
    private fun read(accepts: (String) -> Boolean): List<RepositoryFile> {
        val root = File(Konsist.projectRootPath)
        return root.walkTopDown()
            .onEnter { dir -> dir == root || isReadDirectory(dir.relativeTo(root).invariantSeparatorsPath) }
            .filter { it.isFile }
            .map { file -> file to file.relativeTo(root).invariantSeparatorsPath }
            .filter { (_, path) -> accepts(path) }
            .map { (file, path) -> RepositoryFile(path, file.readText()) }
            .sortedBy { it.relativePath }
            .toList()
    }

    /**
     * True for a directory that is read: no build output, no submodule checkout, and not hidden unless it holds the CI
     * workflows.
     */
    private fun isReadDirectory(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/')
        val isWorkflows = relativePath == ".github" || relativePath.startsWith(".github/")
        val isOwnCode = !BuildOutput.isBuildDirectory(relativePath) && !Submodules.contains(relativePath)
        return isOwnCode && (!name.startsWith(".") || isWorkflows)
    }

    /** True for a Gradle script outside `.github` and for a Kotlin file of any kind inside a source directory. */
    private fun isKotlinSource(relativePath: String): Boolean {
        if (relativePath.startsWith(".github/")) return false
        val isGradleScript = relativePath.endsWith(".gradle.kts")
        val isKotlinFile = relativePath.endsWith(".kt") || relativePath.endsWith(".kts")
        val isInSourceDirectory = "src" in relativePath.split('/').dropLast(1)
        return isGradleScript || (isKotlinFile && isInSourceDirectory)
    }

    /** True for the text documents of [documents]. */
    private fun isTextDocument(relativePath: String): Boolean {
        val segments = relativePath.split('/')
        return when {
            relativePath.endsWith(".md") -> !relativePath.startsWith("docs/history/")
            relativePath.startsWith(".github/") -> relativePath.endsWith(".yml") || relativePath.endsWith(".yaml")
            relativePath.endsWith(".xml") -> "src" in segments && "resources" in segments
            else -> segments.size == 2 && segments[0] == "gradle" && relativePath.endsWith(".toml")
        }
    }
}
