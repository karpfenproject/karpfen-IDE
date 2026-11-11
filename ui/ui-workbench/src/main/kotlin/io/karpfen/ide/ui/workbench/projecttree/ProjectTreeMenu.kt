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
package io.karpfen.ide.ui.workbench.projecttree

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.templates.FileTemplateKind
import java.nio.file.Path

/** A command of the project tree's context menu. The view hands it to `WorkbenchState.runTreeCommand`. */
public sealed interface ProjectTreeCommand {
    /** Create a new [kind] file in [directory], through `WorkbenchState.createFile`. */
    public data class NewFile(public val directory: Path, public val kind: FileTemplateKind) : ProjectTreeCommand

    /** Rename [path], through `WorkbenchState.rename`. */
    public data class Rename(public val path: Path) : ProjectTreeCommand

    /** Delete [path], through `WorkbenchState.delete`. */
    public data class Delete(public val path: Path) : ProjectTreeCommand

    /** Show [path] in Finder or Explorer, through `WorkbenchState.revealInFileManager`. */
    public data class RevealInFileManager(public val path: Path) : ProjectTreeCommand
}

/**
 * An entry of the project tree's context menu.
 *
 * @property label the menu text.
 * @property isEnabled false greys the entry out.
 * @property command what choosing the entry does.
 */
public data class ProjectTreeMenuEntry(
    public val label: String,
    public val isEnabled: Boolean,
    public val command: ProjectTreeCommand,
)

/**
 * The context menu of the project tree. It does not depend on Compose, so the menu is decided here and the view only
 * renders it.
 *
 * The menu offers a New entry for each file template, such as KMeta, KModel, KStates and Environment. The new file goes
 * into the node's directory, or into the parent directory of a file node. Rename… and Delete… follow. Both are
 * disabled for the workspace root. The last entry shows the node in the file manager. Its label depends on [platform]:
 * Reveal in Finder, Show in Explorer or Show in File Manager. It is disabled unless [canRevealInFileManager] returns
 * true. That function is asked each time a menu is built, so creating the menu never touches a platform toolkit.
 */
public class ProjectTreeMenu(
    private val platform: HostPlatform = HostPlatform.current(),
    private val canRevealInFileManager: () -> Boolean = { false },
) {

    /** The label of the file-manager entry on [platform]. */
    public val fileManagerLabel: String
        get() = when (platform) {
            HostPlatform.MAC_OS -> "Reveal in Finder"
            HostPlatform.WINDOWS -> "Show in Explorer"
            HostPlatform.LINUX -> "Show in File Manager"
        }

    /**
     * The entries for the node [path]. [isDirectory] is true if the node is a directory, and [isRoot] is true if it is
     * the workspace root.
     */
    public fun entries(path: Path, isDirectory: Boolean, isRoot: Boolean): List<ProjectTreeMenuEntry> {
        val directory = if (isDirectory) path else path.parent ?: path
        return FileTemplateKind.entries.map { kind ->
            ProjectTreeMenuEntry("New ${kind.displayName}", isEnabled = true, ProjectTreeCommand.NewFile(directory, kind))
        } + listOf(
            ProjectTreeMenuEntry("Rename…", isEnabled = !isRoot, ProjectTreeCommand.Rename(path)),
            ProjectTreeMenuEntry("Delete…", isEnabled = !isRoot, ProjectTreeCommand.Delete(path)),
            ProjectTreeMenuEntry(fileManagerLabel, isEnabled = canRevealInFileManager(), ProjectTreeCommand.RevealInFileManager(path)),
        )
    }
}
