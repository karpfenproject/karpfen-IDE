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
package io.karpfen.ide.ui.workbench.files.view

import androidx.compose.ui.graphics.vector.ImageVector
import io.karpfen.ide.core.workspace.files.FileKind
import io.karpfen.ide.ui.foundation.icons.KarpfenIcons

/**
 * The icon of a file of [kind]. This is the only place in the workbench that maps files to icons. The project tree
 * shows the icon next to the file, and the file's editor tab shows it on the tab strip, so both always agree. The event
 * metamodel looks like any other metamodel. The JSON files share the JSON icon. They are environment descriptors,
 * bridge configurations and layout sidecars.
 */
internal fun fileIconOf(kind: FileKind): ImageVector = when (kind) {
    FileKind.KMETA, FileKind.EVENTS_KMETA -> KarpfenIcons.Kmeta
    FileKind.KMODEL -> KarpfenIcons.Kmodel
    FileKind.KSTATES -> KarpfenIcons.Kstates
    FileKind.ENV_DESCRIPTOR, FileKind.BRIDGE, FileKind.LAYOUT -> KarpfenIcons.Json
    FileKind.OTHER -> KarpfenIcons.File
}
