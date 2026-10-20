package io.karpfen.ide.ui.workbench.statusbar.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.workbench.statusbar.StatusBarUiState

/**
 * The status bar content (spec §8): `Ln x, Col y`, selection length, language, line separator, `UTF-8`, error and
 * warning counts, "Analyzing…".
 */
@Composable
internal fun WorkbenchStatusBar(state: StatusBarUiState, modifier: Modifier = Modifier): Unit =
    TODO("WP-WORKBENCH: status bar")
