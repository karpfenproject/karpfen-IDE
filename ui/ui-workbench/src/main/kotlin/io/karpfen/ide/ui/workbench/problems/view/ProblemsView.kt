package io.karpfen.ide.ui.workbench.problems

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.ui.workbench.state.ProblemFilter
import io.karpfen.ide.ui.workbench.state.ProblemItem
import io.karpfen.ide.ui.workbench.state.ProblemsUiState

/**
 * The Problems tool window (spec §8): problems grouped by file with severity icon, message and `line:col`; error /
 * warning filter; double-click navigates.
 */
@Composable
internal fun ProblemsView(
    state: ProblemsUiState,
    onFilterChange: (ProblemFilter) -> Unit,
    onOpen: (ProblemItem) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-WORKBENCH: problems view")
