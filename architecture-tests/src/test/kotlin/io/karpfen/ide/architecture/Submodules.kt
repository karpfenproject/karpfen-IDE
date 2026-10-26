package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File

/**
 * The git submodules of the repository: checkouts of other repositories, such as karpfen-dsl-tools, that the build uses
 * but that are not part of this code base. Their sources follow their own repository's rules, so the architecture and
 * documentation rules leave them out.
 *
 * The submodule directories are read from `.gitmodules`, the single place git records them, so adding a submodule needs
 * no change here.
 */
internal object Submodules {

    private val PATH_ENTRY = Regex("""^\s*path\s*=\s*(.+?)\s*$""")

    /** The submodule directories relative to the repository root, with '/' separators (e.g. `karpfen-dsl-tools`). */
    val paths: List<String> by lazy { parse(File(Konsist.projectRootPath, ".gitmodules").takeIf { it.isFile }?.readText().orEmpty()) }

    /** The `path` entries of a `.gitmodules` file's [text], normalized to '/' separators without trailing slashes. */
    fun parse(text: String): List<String> =
        text.lineSequence()
            .mapNotNull { line -> PATH_ENTRY.find(line)?.groupValues?.get(1) }
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * True if [relativePath], relative to the repository root with '/' separators and with or without a leading '/',
     * is a submodule directory or lies inside one.
     */
    fun contains(relativePath: String): Boolean {
        val path = relativePath.trimStart('/')
        return paths.any { submodule -> path == submodule || path.startsWith("$submodule/") }
    }
}
