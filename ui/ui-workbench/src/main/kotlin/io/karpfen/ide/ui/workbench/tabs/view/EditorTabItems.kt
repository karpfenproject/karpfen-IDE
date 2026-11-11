/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.workbench.tabs.view

import io.karpfen.ide.ui.foundation.icons.KarpfenIcons
import io.karpfen.ide.ui.foundation.widgets.TabItem
import io.karpfen.ide.ui.workbench.files.view.fileIconOf
import io.karpfen.ide.ui.workbench.tabs.ConflictBanner
import io.karpfen.ide.ui.workbench.tabs.EditorTab

/**
 * The tab strip item of [tab]. It holds presentation only. It shows the title, the tooltip, the dirty dot and the icon
 * of the file's kind, which is the same icon that the project tree shows for the file through [fileIconOf].
 *
 * Only the active tab shows the conflict banner. A tab in conflict, as `EditorTab.conflict` says, is therefore marked
 * on the strip itself. It shows the warning icon instead of the file's icon, and its tooltip adds the banner's message
 * after a dash. A background tab in conflict thus stands out before the user runs Save All or closes it.
 */
internal fun tabItemOf(tab: EditorTab): TabItem {
    val conflict = ConflictBanner.of(tab)
    return TabItem(
        id = tab.path.toString(),
        title = tab.title,
        tooltip = listOfNotNull(tab.tooltip, conflict?.message).joinToString(" — "),
        isDirty = tab.isDirty,
        icon = if (conflict != null) KarpfenIcons.Warning else fileIconOf(tab.kind),
    )
}
