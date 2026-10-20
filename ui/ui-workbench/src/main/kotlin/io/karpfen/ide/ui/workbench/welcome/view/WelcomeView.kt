package io.karpfen.ide.ui.workbench.welcome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The empty state (spec §8): "Open Folder…" and the recent folders. */
@Composable
internal fun WelcomeView(
    recentFolders: List<String>,
    onOpenFolder: () -> Unit,
    onOpenRecent: (String) -> Unit,
    modifier: Modifier = Modifier,
): Unit = TODO("WP-WORKBENCH: welcome view")
