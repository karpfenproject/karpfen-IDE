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
package io.karpfen.ide.app.headless

import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import java.nio.file.Path

/** What a headless run deploys and starts, or why the command line does not name it. */
internal sealed interface RunSelection {

    /**
     * The command line names a descriptor and bridge files of the project.
     *
     * @property descriptor the environment descriptor to run.
     * @property bridges the bridge files to start with the run, in the order they were named.
     */
    data class Chosen(val descriptor: Path, val bridges: List<Path>) : RunSelection

    /** The command line names something the project does not have, as [message] says. That is a usage error. */
    data class Wrong(val message: String) : RunSelection

    companion object {
        /**
         * What [command] selects among the files of the project at [root], whose tree is [tree].
         *
         * The descriptor is the one `--descriptor` names, which must be an environment descriptor of the project.
         * Without the option it is the only descriptor of the project. A project with none, or with several, is a
         * usage error, and the message lists them. Each `--bridge` must name a bridge file of the project, and the
         * message of a wrong one lists the bridge files there are. The lists follow the order of the project tree,
         * which puts folders before files.
         */
        fun of(command: HeadlessCommand.Run, root: Path, tree: WorkspaceTree): RunSelection {
            val descriptors = tree.files(FileKind.ENV_DESCRIPTOR).map { it.path }
            val descriptor = when {
                command.descriptor != null -> command.descriptor.takeIf { it in descriptors }
                    ?: return Wrong("${RunLines.nameOf(root, command.descriptor)} is no environment descriptor of the project; ${listed(root, descriptors, DESCRIPTORS)}")
                descriptors.size == 1 -> descriptors.single()
                descriptors.isEmpty() -> return Wrong("the project has no environment descriptor (*.kenv.json)")
                else -> return Wrong("the project has several environment descriptors; choose one with --descriptor: ${names(root, descriptors)}")
            }
            val bridgeFiles = tree.files(FileKind.BRIDGE).map { it.path }
            command.bridges.firstOrNull { it !in bridgeFiles }?.let { wrong ->
                return Wrong("${RunLines.nameOf(root, wrong)} is no bridge file of the project; ${listed(root, bridgeFiles, BRIDGES)}")
            }
            return Chosen(descriptor, command.bridges)
        }

        /** The words of the environment descriptors for the error messages. */
        private const val DESCRIPTORS = "environment descriptors"

        /** The words of the bridge files for the error messages. */
        private const val BRIDGES = "bridge files"

        /** The sentence that lists the [files] of the [kind] the project at [root] has, or says it has none. */
        private fun listed(root: Path, files: List<Path>, kind: String): String =
            if (files.isEmpty()) "the project has no $kind" else "its $kind are ${names(root, files)}"

        /** [files] relative to [root], separated by commas. */
        private fun names(root: Path, files: List<Path>): String = files.joinToString(", ") { RunLines.nameOf(root, it) }
    }
}
