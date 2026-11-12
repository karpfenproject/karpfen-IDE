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
package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.workbench.bridges.BridgeDraft
import io.karpfen.ide.ui.workbench.bridges.BridgeDrafts
import io.karpfen.ide.ui.workbench.bridges.BridgeFormState
import io.karpfen.ide.ui.workbench.bridges.BridgeLoader
import io.karpfen.ide.ui.workbench.bridges.BridgeSuggestions
import io.karpfen.ide.ui.workbench.dialog.DialogRequest
import io.karpfen.ide.ui.workbench.dialog.DialogResponse
import io.karpfen.ide.ui.workbench.dialog.DialogState
import io.karpfen.ide.ui.workbench.projecttree.ProjectTreeState
import io.karpfen.ide.ui.workbench.settings.SettingsRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Opens, saves and closes the form that configures a bridge file. A bridge file is never edited as text in the IDE.
 * Opening one, from the project tree or through Configure Bridge in the Run menu, shows the form over the workbench.
 *
 * The form names the bridge, and a changed name renames the file when the form is saved. The bridges that are
 * remembered for the next run follow the new name.
 *
 * The form offers the events, the properties and the state machines of the project to pick, as the analysis of the
 * workspace knows them when the form opens.
 *
 * Other tools may change a bridge file at any time. So the form reads the file whenever it opens, leaves out what it
 * cannot read and says so. When the file changed on disk while the form was open, saving asks before it writes over
 * that change. Blocking file work runs on [ioDispatcher]. Failures become error dialogs.
 */
internal class BridgeFormFlow(
    private val holder: WorkspaceHolder,
    private val projectTree: ProjectTreeState,
    private val dialogs: DialogState,
    private val settings: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val shown = MutableStateFlow<BridgeFormState?>(null)

    /** The form that is open, or null. */
    val form: StateFlow<BridgeFormState?> get() = shown.asStateFlow()

    /**
     * Asks which bridge of the workspace to configure, or whether to make a new one, and opens its form. A workspace
     * without bridge files goes straight to the new one.
     */
    suspend fun configure() {
        val workspace = holder.workspace.value ?: return
        val files = bridgeFilesOf(workspace)
        if (files.isEmpty()) return create(workspace)
        when (val answer = dialogs.ask(DialogRequest.ChooseBridgeFile(workspace.root, files))) {
            is DialogResponse.Chosen -> files.getOrNull(answer.index)?.let { open(it) } ?: create(workspace)
            DialogResponse.Cancel -> Unit
        }
    }

    /** Reads the bridge file [path] and opens its form. */
    suspend fun open(path: Path) {
        val workspace = holder.workspace.value ?: return
        val text = try {
            withContext(ioDispatcher) { Files.readString(path) }
        } catch (e: IOException) {
            dialogs.showError("Cannot open ${path.fileName}", e.reason())
            return
        }
        val reading = if (text.isBlank()) null else BridgeConfigFile.read(text)
        val directory = path.parent ?: workspace.root
        shown.value = BridgeFormState(
            directory = directory,
            original = path,
            fileProblems = reading?.problems.orEmpty(),
            initial = reading?.config?.let(BridgeDrafts::of) ?: BridgeDraft(),
            loadedText = text,
            suggestions = BridgeSuggestions.of(workspace.analysis.value),
            taken = namesIn(workspace, directory, except = path),
        )
    }

    /**
     * Writes the open form to its file, refreshes the workspace, shows the file in the project tree and closes the
     * form. Does nothing while the form has problems.
     *
     * If the original file is not what the form read, because another tool changed or removed it meanwhile, it asks
     * first and keeps the form open on Cancel. A form with a changed name writes the file of the new name and removes
     * the original one. It never writes over another file: if a file of the new name exists, an error dialog says so
     * and the form stays open.
     */
    suspend fun save() {
        val form = shown.value ?: return
        val config = BridgeDrafts.configOf(form.draft.value)?.takeIf { form.problems.value.isEmpty() } ?: return
        val original = form.original
        val target = form.file
        try {
            if (target != original && withContext(ioDispatcher) { Files.exists(target) }) {
                dialogs.showError("Cannot save ${target.fileName}", "A file with this name exists already. Choose another name.")
                return
            }
            if (original != null) {
                val onDisk = withContext(ioDispatcher) { readOrNull(original) }
                if (onDisk != form.loadedText && dialogs.ask(DialogRequest.ConfirmOverwrite(listOf(original))) != DialogResponse.Confirm) return
            }
            withContext(ioDispatcher) {
                Files.createDirectories(form.directory)
                Files.writeString(target, BridgeConfigFile.write(config))
                if (original != null && original != target) Files.deleteIfExists(original)
            }
        } catch (e: IOException) {
            dialogs.showError("Cannot save ${target.fileName}", e.reason())
            return
        }
        shown.value = null
        val workspace = holder.workspace.value
        if (workspace != null && original != null && original != target) remember(workspace, original, target)
        workspace?.refresh()
        projectTree.reveal(target)
    }

    /** Closes the open form without saving. */
    fun close() {
        shown.value = null
    }

    /** Opens an empty form for a new bridge in the folder of [workspace]. The form asks for the name. */
    private fun create(workspace: Workspace) {
        shown.value = BridgeFormState(
            directory = workspace.root,
            original = null,
            fileProblems = emptyList(),
            initial = BridgeDrafts.of(BridgeConfig(BridgeKind.HTTP)),
            loadedText = null,
            suggestions = BridgeSuggestions.of(workspace.analysis.value),
            taken = namesIn(workspace, workspace.root, except = null),
        )
    }

    /** Lets the bridges remembered for the next run name [renamed] where they named [original]. */
    private suspend fun remember(workspace: Workspace, original: Path, renamed: Path) {
        val before = BridgeLoader.nameOf(workspace.root, original)
        if (before !in settings.settings.value.activeBridges) return
        val after = BridgeLoader.nameOf(workspace.root, renamed)
        settings.update { stored -> stored.copy(activeBridges = stored.activeBridges.map { if (it == before) after else it }) }
    }

    /** The bridge files of [workspace] in the order of the project tree. */
    private fun bridgeFilesOf(workspace: Workspace): List<Path> = workspace.files.value.files(FileKind.BRIDGE).map { it.path }

    /** The names of the bridges in [directory], without the one in the file [except]. */
    private fun namesIn(workspace: Workspace, directory: Path, except: Path?): Set<String> =
        bridgeFilesOf(workspace).filter { it.parent == directory && it != except }.mapTo(HashSet(), BridgeFormState::nameOf)

    /** The text of [file], or null if there is no such file. */
    private fun readOrNull(file: Path): String? = try {
        Files.readString(file)
    } catch (_: NoSuchFileException) {
        null
    }
}
