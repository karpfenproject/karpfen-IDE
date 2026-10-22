package io.karpfen.ide.ui.workbench.files.view

import androidx.compose.ui.graphics.vector.ImageVector
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons

/**
 * The icon of a file of [kind], the one file-icon rule of the workbench: the project tree shows it next to the file and
 * the file's editor tab shows it on the tab strip, so both always agree. The event metamodel looks like any metamodel,
 * and the JSON files (environment descriptors, bridge configurations, layout sidecars) share the JSON icon.
 */
internal fun fileIconOf(kind: FileKind): ImageVector = when (kind) {
    FileKind.KMETA, FileKind.EVENTS_KMETA -> KarpfenIcons.Kmeta
    FileKind.KMODEL -> KarpfenIcons.Kmodel
    FileKind.KSTATES -> KarpfenIcons.Kstates
    FileKind.ENV_DESCRIPTOR, FileKind.BRIDGE, FileKind.LAYOUT -> KarpfenIcons.Json
    FileKind.OTHER -> KarpfenIcons.File
}
