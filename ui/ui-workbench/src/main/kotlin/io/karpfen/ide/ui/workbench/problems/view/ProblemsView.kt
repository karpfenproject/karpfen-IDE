package io.karpfen.ide.ui.workbench.problems.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.ui.workbench.problems.ProblemFilter
import io.karpfen.ide.ui.workbench.problems.ProblemsUiState

/**
 * The Problems tool window (spec §8): problems grouped by file with severity icon, message and `line:col`; error /
 * warning filter; double-click calls [onNavigate] with the item's `ProblemItem.location` (versioned).
 */
@Composable
internal fun ProblemsView(
    state: ProblemsUiState,
    onFilterChange: (ProblemFilter) -> Unit,
    onNavigate: (Location) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-WORKBENCH: problems view")
