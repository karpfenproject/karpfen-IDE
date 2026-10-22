package io.karpfen.ide.ui.workbench.state

import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.editor.state.EditorHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/**
 * Collects each editor's `navigationRequests` from opening its tab until closing it and hands every target to
 * [navigate] (the mediator's `navigateTo` intent), so a definition lookup that finishes after a tab switch still
 * navigates and navigation never depends on a composable being on screen.
 */
internal class EditorNavigationCollector(
    private val scope: CoroutineScope,
    private val navigate: (Location) -> Unit,
) {
    private val collectors = IdentityHashMap<EditorHandle, Job>()

    /** Starts collecting [editor]'s navigation requests. */
    fun start(editor: EditorHandle) {
        if (editor in collectors) return
        collectors[editor] = scope.launch(start = CoroutineStart.UNDISPATCHED) { editor.navigationRequests.collect { navigate(it) } }
    }

    /** Stops collecting [editor]'s navigation requests. */
    fun stop(editor: EditorHandle) {
        collectors.remove(editor)?.cancel()
    }
}
