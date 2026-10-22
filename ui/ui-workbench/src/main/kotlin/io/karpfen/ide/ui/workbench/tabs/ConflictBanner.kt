package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ExternalChangeKind

/** A button of the external-change banner: its [label] and the [resolution] it reports. */
internal data class ConflictBannerAction(val label: String, val resolution: ConflictResolution)

/**
 * The external-change banner of a tab, chosen from [EditorTab.conflict]: a file changed on disk offers Reload / Keep
 * mine, a file deleted on disk Close / Keep mine; both Reload and Close report [ConflictResolution.RELOAD] ("take the
 * disk state"), which for a deleted file the workbench turns into closing the tab.
 */
internal data class ConflictBanner(val message: String, val actions: List<ConflictBannerAction>) {

    companion object {
        /** The banner for [tab], or null without a conflict. */
        fun of(tab: EditorTab): ConflictBanner? = when (tab.conflict) {
            null -> null
            ExternalChangeKind.MODIFIED -> ConflictBanner(
                "${tab.title} changed on disk while it has unsaved changes.",
                listOf(ConflictBannerAction("Reload", ConflictResolution.RELOAD), KEEP_MINE),
            )
            ExternalChangeKind.DELETED -> ConflictBanner(
                "${tab.title} was deleted on disk.",
                listOf(ConflictBannerAction("Close", ConflictResolution.RELOAD), KEEP_MINE),
            )
        }

        private val KEEP_MINE = ConflictBannerAction("Keep mine", ConflictResolution.KEEP_MINE)
    }
}
