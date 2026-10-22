package io.karpfen.ide.architecture

/**
 * The one rule by which the repository tells build output from sources. Build output is a directory named `build`
 * outside the source directories, that is with no `src` segment before it: the `build` directories that Gradle writes
 * for the root, for build-logic and for every module. A source package named `build`, such as
 * `core/core-text/src/main/kotlin/io/karpfen/ide/core/text/build`, is sources like any other package: the documentation
 * style rules guard it, the package layout rules look into it, and git keeps it, because the `.gitignore` names only
 * the `build` directories that Gradle writes, anchored at the root. The inputs of the architecture tests, declared in
 * their build script, leave out the same directories.
 *
 * Paths are relative to the repository root, with '/' separators; a leading separator, as Konsist writes project paths,
 * is ignored.
 */
internal object BuildOutput {

    /** The name of the directories that hold build output. */
    private const val BUILD = "build"

    /** The name of the directory that holds the source sets of a module. */
    private const val SOURCES = "src"

    /** True for the directory at [relativePath] if it holds build output, such as `core/core-text/build`. */
    fun isBuildDirectory(relativePath: String): Boolean {
        val segments = segmentsOf(relativePath)
        return segments.lastOrNull() == BUILD && SOURCES !in segments.dropLast(1)
    }

    /** True for the file at [relativePath] if it lies inside build output. */
    fun contains(relativePath: String): Boolean {
        val directories = segmentsOf(relativePath).dropLast(1)
        val build = directories.indexOf(BUILD)
        val sources = directories.indexOf(SOURCES)
        return build >= 0 && (sources < 0 || build < sources)
    }

    /** The segments of [relativePath], without the empty segment of a leading separator. */
    private fun segmentsOf(relativePath: String): List<String> = relativePath.removePrefix("/").split('/')
}
