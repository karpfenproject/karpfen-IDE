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
        val files = workspace.files.value.files(FileKind.BRIDGE).map { it.path }
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
        shown.value = BridgeFormState(
            file = path,
            name = BridgeLoader.nameOf(workspace.root, path),
            isNew = false,
            fileProblems = reading?.problems.orEmpty(),
            initial = reading?.config?.let(BridgeDrafts::of) ?: BridgeDraft(),
            loadedText = text,
            suggestions = BridgeSuggestions.of(workspace.analysis.value),
        )
    }

    /**
     * Writes the open form to its file, refreshes the workspace, shows the file in the project tree and closes the
     * form. Does nothing while the form has problems. If the file is not what the form read, because another tool
     * changed it or made it meanwhile, it asks first and keeps the form open on Cancel.
     */
    suspend fun save() {
        val form = shown.value ?: return
        val config = BridgeDrafts.configOf(form.draft.value) ?: return
        try {
            val onDisk = withContext(ioDispatcher) { readOrNull(form.file) }
            if (onDisk != form.loadedText && dialogs.ask(DialogRequest.ConfirmOverwrite(listOf(form.file))) != DialogResponse.Confirm) return
            withContext(ioDispatcher) {
                form.file.parent?.let(Files::createDirectories)
                Files.writeString(form.file, BridgeConfigFile.write(config))
            }
        } catch (e: IOException) {
            dialogs.showError("Cannot save ${form.file.fileName}", e.reason())
            return
        }
        shown.value = null
        holder.workspace.value?.refresh()
        projectTree.reveal(form.file)
    }

    /** Closes the open form without saving. */
    fun close() {
        shown.value = null
    }

    /** Asks for the name of a new bridge file in the folder of [workspace] and opens an empty form for it. */
    private suspend fun create(workspace: Workspace) {
        val answer = dialogs.ask(DialogRequest.NewBridgeFile(workspace.root))
        val name = (answer as? DialogResponse.Text)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val fileName = if (name.endsWith(BridgeConfigFile.SUFFIX, ignoreCase = true)) name else name + BridgeConfigFile.SUFFIX
        val file = workspace.root.resolve(fileName).normalize()
        if (file.parent != workspace.root) {
            dialogs.showError("Cannot create $fileName", "The name must not name another folder.")
            return
        }
        if (withContext(ioDispatcher) { Files.exists(file) }) {
            dialogs.showError("Cannot create $fileName", "A file with this name exists already.")
            return
        }
        shown.value = BridgeFormState(
            file = file,
            name = fileName,
            isNew = true,
            fileProblems = emptyList(),
            initial = BridgeDrafts.of(BridgeConfig(BridgeKind.HTTP)),
            loadedText = null,
            suggestions = BridgeSuggestions.of(workspace.analysis.value),
        )
    }

    /** The text of [file], or null if there is no such file. */
    private fun readOrNull(file: Path): String? = try {
        Files.readString(file)
    } catch (_: NoSuchFileException) {
        null
    }
}
