package io.karpfen.ide.ui.workbench.tabs.view

import io.karpfen.ide.core.document.KarpfenLanguage
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TabItem
import io.karpfen.ide.ui.workbench.tabs.ConflictBanner
import io.karpfen.ide.ui.workbench.tabs.EditorTab

/**
 * The tab-strip item of [tab] (presentation only): title, tooltip, dirty dot and the language's icon. Only the active
 * tab shows the conflict banner, so a tab in conflict (`EditorTab.conflict`) is marked on the strip itself — the warning
 * icon instead of the language's, and the banner's message in its tooltip — and a background tab in conflict stands
 * out before Save All or closing it.
 */
internal fun tabItemOf(tab: EditorTab): TabItem {
    val conflict = ConflictBanner.of(tab)
    return TabItem(
        id = tab.path.toString(),
        title = tab.title,
        tooltip = listOfNotNull(tab.tooltip, conflict?.message).joinToString(" — "),
        isDirty = tab.isDirty,
        icon = if (conflict != null) KarpfenIcons.Warning else iconOf(tab.language),
    )
}

/** The file icon of [language]. */
private fun iconOf(language: KarpfenLanguage) = when (language) {
    KarpfenLanguage.KMETA -> KarpfenIcons.Kmeta
    KarpfenLanguage.KMODEL -> KarpfenIcons.Kmodel
    KarpfenLanguage.KSTATES -> KarpfenIcons.Kstates
}
