package io.karpfen.ide.ui.workbench.projecttree

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path

/** A command of the project tree's context menu; the view hands it to the matching `WorkbenchState` intent. */
public sealed interface ProjectTreeCommand {
    /** New [kind] file in [directory] (`WorkbenchState.createFile`). */
    public data class NewFile(public val directory: Path, public val kind: FileTemplateKind) : ProjectTreeCommand

    /** Rename [path] (`WorkbenchState.rename`). */
    public data class Rename(public val path: Path) : ProjectTreeCommand

    /** Delete [path] (`WorkbenchState.delete`). */
    public data class Delete(public val path: Path) : ProjectTreeCommand

    /** Show [path] in Finder/Explorer (`WorkbenchState.revealInFileManager`). */
    public data class RevealInFileManager(public val path: Path) : ProjectTreeCommand
}

/** An entry of the project tree's context menu. */
public data class ProjectTreeMenuEntry(
    public val label: String,
    public val isEnabled: Boolean,
    public val command: ProjectTreeCommand,
)

/**
 * The project tree's context menu (spec §8): New KMeta/KModel/KStates/Environment file (in the node's directory, or
 * the file's parent directory), Rename…, Delete… (both disabled for the workspace root) and Reveal in Finder / Show in
 * Explorer / Show in File Manager (by [platform]; disabled unless [canRevealInFileManager]). Compose-free, so the menu
 * is decided here and the view only renders it.
 */
public class ProjectTreeMenu(
    private val platform: HostPlatform = HostPlatform.current(),
    private val canRevealInFileManager: Boolean = false,
) {

    /** The label of the file-manager entry on [platform]. */
    public val fileManagerLabel: String
        get() = when (platform) {
            HostPlatform.MAC_OS -> "Reveal in Finder"
            HostPlatform.WINDOWS -> "Show in Explorer"
            HostPlatform.LINUX -> "Show in File Manager"
        }

    /** The entries for the node [path] ([isDirectory]: a directory; [isRoot]: the workspace root). */
    public fun entries(path: Path, isDirectory: Boolean, isRoot: Boolean): List<ProjectTreeMenuEntry> {
        val directory = if (isDirectory) path else path.parent ?: path
        return FileTemplateKind.entries.map { kind ->
            ProjectTreeMenuEntry("New ${kind.displayName}", isEnabled = true, ProjectTreeCommand.NewFile(directory, kind))
        } + listOf(
            ProjectTreeMenuEntry("Rename…", isEnabled = !isRoot, ProjectTreeCommand.Rename(path)),
            ProjectTreeMenuEntry("Delete…", isEnabled = !isRoot, ProjectTreeCommand.Delete(path)),
            ProjectTreeMenuEntry(fileManagerLabel, isEnabled = canRevealInFileManager, ProjectTreeCommand.RevealInFileManager(path)),
        )
    }
}
