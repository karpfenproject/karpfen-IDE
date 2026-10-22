package io.karpfen.ide.core.workspace.environment

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/**
 * Whether the files descriptors name are there: the one rule that the workspace checks a descriptor and builds its
 * modeling context by, and that the editor's hover, go to definition and completion follow, so none of them treats a
 * file as present that the descriptor's findings report missing.
 *
 * A descriptor names a file by a path relative to its own directory ([DescriptorPaths.resolve]). The file is there only
 * if it is a file of the workspace [tree]: a file that is missing, lies in an ignored directory or outside the
 * workspace, or is a directory, is missing even where it exists on disk, because the workspace neither shows, watches
 * nor analyzes it. A file of the tree fits a role if it is written in the role's language ([FileKind.fits]). Pure;
 * one per tree.
 */
public class DescriptorFiles(private val tree: WorkspaceTree) {

    /** What [value], named for [role] by a descriptor in [directory], refers to in the tree. */
    public fun lookUp(directory: Path, value: String, role: DescriptorFileRole): NamedFile {
        val path = DescriptorPaths.resolve(directory, value) ?: return NamedFile.NoPath
        val file = tree.find(path) as? WorkspaceNode.File ?: return NamedFile.Missing(path)
        val language = role.language
        return if (language != null && !file.kind.fits(role)) NamedFile.WrongLanguage(file, language) else NamedFile.Present(file)
    }

    /** Every file of the tree a key of [role] can name without a finding, in the tree's order. */
    public fun candidates(role: DescriptorFileRole): List<WorkspaceNode.File> = tree.files().filter { it.kind.fits(role) }
}

/** What a file path in a descriptor refers to, as [DescriptorFiles.lookUp] finds it. */
public sealed interface NamedFile {

    /** The file of the workspace tree the path names, or null where it names none. */
    public val file: WorkspaceNode.File? get() = null

    /** The value is no valid path on this platform. */
    public data object NoPath : NamedFile

    /** The value names [path], which is no file of the workspace tree. */
    public data class Missing(public val path: Path) : NamedFile

    /** The value names [file] of the tree, which is not written in the [language] its role needs. */
    public data class WrongLanguage(override val file: WorkspaceNode.File, public val language: KarpfenLanguage) : NamedFile

    /** The value names [file] of the tree, which fits its role. */
    public data class Present(override val file: WorkspaceNode.File) : NamedFile
}
