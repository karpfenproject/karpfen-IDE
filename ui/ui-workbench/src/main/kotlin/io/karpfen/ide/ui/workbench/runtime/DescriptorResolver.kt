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
package io.karpfen.ide.ui.workbench.runtime

import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path

/** Which environment descriptor the Run command picks when it is not given one. */
public sealed interface DescriptorPick {
    /**
     * The command runs [descriptor] without asking. It is the active editor's descriptor, or the only descriptor of
     * the workspace.
     *
     * @property descriptor the descriptor.
     */
    public data class Picked(public val descriptor: Path) : DescriptorPick

    /**
     * The command asks which of [descriptors] to run.
     *
     * @property descriptors the descriptors of the workspace in project-tree order.
     */
    public data class Choice(public val descriptors: List<Path>) : DescriptorPick

    /** The workspace has no descriptor. The command tells the user how to create one. */
    public data object None : DescriptorPick
}

/**
 * Resolves the environment descriptor that a run deploys. A descriptor given by the caller wins. Otherwise it is the
 * active editor's file if that is a descriptor. Otherwise it is the only descriptor of the workspace. Otherwise the
 * user chooses among the descriptors, and the last choice in this workspace is preselected. Without any descriptor an
 * error dialog tells the user how to create one. Use the class from the UI thread only.
 *
 * @param root the root folder of the workspace.
 * @param support asks the user and shows the error.
 */
internal class DescriptorResolver(private val root: Path, private val support: RunSupport) {

    /** The descriptor the user chose last, or null before the first choice. */
    private var lastChoice: Path? = null

    /**
     * The descriptor to run. That is [given] if it is set, and otherwise what [pick] says. Returns null if there is no
     * descriptor or the user cancelled.
     */
    suspend fun resolve(given: Path?, pick: DescriptorPick): Path? {
        if (given != null) return given.toAbsolutePath().normalize()
        return when (pick) {
            is DescriptorPick.Picked -> pick.descriptor
            is DescriptorPick.Choice -> {
                val preselected = lastChoice?.takeIf { it in pick.descriptors }
                support.chooseDescriptor(root, pick.descriptors, preselected)?.also { lastChoice = it }
            }
            DescriptorPick.None -> {
                support.showError(NO_DESCRIPTOR_TITLE, NO_DESCRIPTOR_MESSAGE)
                null
            }
        }
    }

    companion object {
        /** The title of the error dialog without a descriptor. */
        const val NO_DESCRIPTOR_TITLE: String = "Nothing to run"

        /** The message of the error dialog without a descriptor. */
        const val NO_DESCRIPTOR_MESSAGE: String =
            "The workspace has no environment descriptor (*.kenv.json). Create one with New Environment File in the " +
                "context menu of a folder in the Project tool window; it names the files to run and the objects the " +
                "state machines run attached to."

        /** What the Run command picks among the workspace's [descriptors], given the file of the [active] editor. */
        fun pick(active: Path?, descriptors: List<Path>): DescriptorPick = when {
            active != null && FileKind.of(active.fileName.toString()) == FileKind.ENV_DESCRIPTOR -> DescriptorPick.Picked(active)
            descriptors.size == 1 -> DescriptorPick.Picked(descriptors.single())
            descriptors.isEmpty() -> DescriptorPick.None
            else -> DescriptorPick.Choice(descriptors)
        }
    }
}
