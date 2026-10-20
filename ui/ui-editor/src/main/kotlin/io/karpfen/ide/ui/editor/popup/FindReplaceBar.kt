package io.karpfen.ide.ui.editor.popup

import androidx.compose.runtime.Composable
import io.karpfen.ide.ui.editor.model.find.FindQuery
import io.karpfen.ide.ui.editor.model.find.FindResult
import io.karpfen.ide.ui.editor.state.FindBarMode
import io.karpfen.ide.ui.editor.theme.EditorTheme

/**
 * The find/replace bar (spec §7.2): query field with toggles Aa (case), W (whole word), .* (regex), an "n/m" counter,
 * an error hint for invalid regexes, Enter/Shift+Enter for next/previous, and in [FindBarMode.REPLACE] a replace
 * field with Replace / Replace All. Escape closes ([onClose]; the caller returns focus to the editor).
 */
@Composable
internal fun FindReplaceBar(
    mode: FindBarMode,
    state: FindResult,
    theme: EditorTheme,
    onQueryChange: (FindQuery) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onClose: () -> Unit,
): Unit = TODO("WP-EDITOR-POPUPS: find/replace bar")
