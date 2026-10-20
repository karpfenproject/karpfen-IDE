package io.karpfen.ide.ui.workbench.testing

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.core.document.language.LanguageRegistry
import io.karpfen.ide.core.document.registry.defaultLanguageRegistry
import io.karpfen.ide.core.document.session.DocumentId
import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.document.session.DocumentSessionConfig
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.services.defaultLanguageServices
import io.karpfen.ide.core.text.EditableText
import io.karpfen.ide.core.text.LineSeparator
import io.karpfen.ide.core.workspace.Workspace
import io.karpfen.ide.core.workspace.analysis.ProjectAnalysis
import io.karpfen.ide.core.workspace.context.ContextResolution
import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ConflictResult
import io.karpfen.ide.core.workspace.document.DocumentManager
import io.karpfen.ide.core.workspace.document.ExternalChange
import io.karpfen.ide.core.workspace.document.OpenDocument
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.core.workspace.files.WorkspaceTree
import io.karpfen.ide.core.workspace.problems.WorkspaceProblems
import io.karpfen.ide.ui.editor.model.action.defaultEditorKeymap
import io.karpfen.ide.ui.editor.state.CodeEditorState
import io.karpfen.ide.ui.editor.state.EditorSettings
import io.karpfen.ide.ui.foundation.keys.HostPlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path

/** A [Workspace] whose flows a test sets directly (for the sub-states that only read them). */
class FakeWorkspace(override val root: Path) : Workspace {
    override val languages: LanguageRegistry = defaultLanguageRegistry()
    override val files = MutableStateFlow(WorkspaceTree.empty(root))
    override val contexts = MutableStateFlow(ContextResolution.EMPTY)
    override val analysis = MutableStateFlow(ProjectAnalysis.EMPTY)
    override val problems = MutableStateFlow(WorkspaceProblems.EMPTY)
    override val isAnalyzing = MutableStateFlow(false)
    override val documents: DocumentManager get() = error("not used")

    override suspend fun refresh() = Unit

    override suspend fun awaitAnalysis(): ProjectAnalysis = analysis.value

    override fun close() = Unit
}

/**
 * An [OpenDocument] over a real [DocumentSession] (the language registry's tokenizer and parser on [dispatcher]) whose
 * dirty flag, separator and conflict a test sets directly.
 */
class FakeOpenDocument(
    path: Path,
    text: String,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) : OpenDocument {
    override val id: DocumentId = DocumentId.of(path)
    override val fileKind: FileKind = FileKind.of(id.fileName)
    override val session: DocumentSession = DocumentSession(
        id = id,
        support = defaultLanguageRegistry().support(requireNotNull(KarpfenLanguage.fromFileName(id.fileName))),
        text = EditableText(text),
        scope = scope,
        config = DocumentSessionConfig(tokenizeDispatcher = dispatcher, parseDispatcher = dispatcher),
    )
    override val isDirty = MutableStateFlow(false)
    override val lineSeparator = MutableStateFlow(LineSeparator.LF)
    override val conflict = MutableStateFlow<ExternalChange?>(null)
    val resolutions = mutableListOf<ConflictResolution>()

    override suspend fun resolveConflict(resolution: ConflictResolution): ConflictResult {
        resolutions.add(resolution)
        return ConflictResult.Kept
    }
}

/** A [CodeEditorState] that records whether it was closed. */
class ClosingSpyEditor(private val delegate: CodeEditorState) : CodeEditorState by delegate {
    var closed: Boolean = false
        private set

    override fun close() {
        closed = true
        delegate.close()
    }
}

/** A real editor (macOS keymap, empty-analysis language services) of [document] on [dispatcher]. */
fun editorOf(
    document: OpenDocument,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    settings: MutableStateFlow<EditorSettings> = MutableStateFlow(EditorSettings()),
    languageServices: LanguageServices = defaultLanguageServices(MutableStateFlow(ProjectAnalysis.EMPTY)),
): ClosingSpyEditor = ClosingSpyEditor(
    CodeEditorState(
        document.session, languageServices, scope, TestClipboard(), settings,
        keymap = defaultEditorKeymap(HostPlatform.MAC_OS),
        backgroundDispatcher = dispatcher,
    ),
)
