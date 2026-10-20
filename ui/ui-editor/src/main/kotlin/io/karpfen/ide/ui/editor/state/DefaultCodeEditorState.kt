package io.karpfen.ide.ui.editor.state

import io.karpfen.ide.core.document.session.DocumentSession
import io.karpfen.ide.core.lang.BracketMatch
import io.karpfen.ide.core.lang.LanguageServices
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.model.action.ClipboardPort
import io.karpfen.ide.ui.editor.model.action.EditorAction
import io.karpfen.ide.ui.editor.model.action.EditorActionRegistry
import io.karpfen.ide.ui.editor.model.action.EditorKeymap
import io.karpfen.ide.ui.editor.model.caret.CaretModel
import io.karpfen.ide.ui.editor.model.completion.CompletionSessionModel
import io.karpfen.ide.ui.editor.model.find.FindModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default [CodeEditorState]: creates the models, implements `EditorActionContext`/`EditorUiPort` for the actions and
 * `CompletionSource` for completion, and computes highlights and bracket matches off the UI thread.
 */
internal class DefaultCodeEditorState(
    override val session: DocumentSession,
    override val services: LanguageServices,
    private val parentScope: CoroutineScope,
    private val clipboard: ClipboardPort,
    override val settings: StateFlow<EditorSettings>,
    override val keymap: EditorKeymap,
    override val actions: EditorActionRegistry,
) : CodeEditorState {

    override val caret: CaretModel get() = TODO("WP-EDITOR-VIEW: caret model")
    override val find: FindModel get() = TODO("WP-EDITOR-VIEW: find model")
    override val completion: CompletionSessionModel get() = TODO("WP-EDITOR-VIEW: completion model")
    override val overlays: EditorOverlayController get() = TODO("WP-EDITOR-VIEW: overlay controller")
    override val viewport: EditorViewport get() = TODO("WP-EDITOR-VIEW: viewport")
    override val highlights: StateFlow<HighlightState> get() = TODO("WP-EDITOR-VIEW: highlights")
    override val bracketMatch: StateFlow<BracketMatch?> get() = TODO("WP-EDITOR-VIEW: bracket match")
    override val scrollRequests: SharedFlow<ScrollRequest> get() = TODO("WP-EDITOR-VIEW: scroll requests")
    override val navigationRequests: SharedFlow<Location> get() = TODO("WP-EDITOR-VIEW: navigation requests")
    override val focusRequests: SharedFlow<Unit> get() = TODO("WP-EDITOR-VIEW: focus requests")

    override fun execute(actionId: String): Boolean = TODO("WP-EDITOR-VIEW: execute action by id")

    override fun execute(action: EditorAction): Boolean = TODO("WP-EDITOR-VIEW: execute action")

    override fun type(text: String): Unit = TODO("WP-EDITOR-VIEW: typing path")

    override fun scrollTo(offset: Int, center: Boolean): Unit = TODO("WP-EDITOR-VIEW: scroll request")

    override fun goToDefinition(offset: Int): Unit = TODO("WP-EDITOR-VIEW: go to definition")

    override fun hoverAt(offset: Int): HoverContent? = TODO("WP-EDITOR-VIEW: hover content")

    override fun requestFocus(): Unit = TODO("WP-EDITOR-VIEW: focus request")

    override fun close(): Unit = TODO("WP-EDITOR-VIEW: close")
}
