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

import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceNode
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import java.nio.file.Path

/**
 * The sections of the Project tool window, from top to bottom. A line divides one section from the next. The kind of
 * a file decides its section, wherever in the project the file lies.
 */
internal enum class ProjectSection {
    /** The files about the project itself: its project file and its environment descriptors. */
    META,

    /** The folder with the model files and everything else. */
    FILES,

    /** The bridge files. */
    BRIDGES,
    ;

    companion object {
        /**
         * The section of [file] in the project with the folder [root]. The project file counts only in the root
         * folder itself, because a file of that name further down belongs to another project.
         */
        fun of(file: WorkspaceNode.File, root: Path): ProjectSection = when {
            file.kind == FileKind.BRIDGE -> BRIDGES
            file.kind == FileKind.ENV_DESCRIPTOR -> META
            file.name == ProjectSettings.FILE_NAME && file.path.parent == root -> META
            else -> FILES
        }
    }
}
