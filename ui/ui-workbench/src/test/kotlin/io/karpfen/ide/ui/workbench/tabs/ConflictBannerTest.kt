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
package io.karpfen.ide.ui.workbench.tabs

import io.karpfen.ide.core.workspace.document.ConflictResolution
import io.karpfen.ide.core.workspace.document.ExternalChangeKind
import io.karpfen.ide.core.workspace.files.FileKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [ConflictBanner]: the banner per `EditorTab.conflict`, whose Reload and Close both take the disk state. */
class ConflictBannerTest {

    private val tab =
        EditorTab(Path.of("/ws/a.kstates").toAbsolutePath(), "a.kstates", "a.kstates", FileKind.KSTATES, isDirty = true)

    @Test
    fun `modified offers Reload and Keep mine, deleted offers Close and Keep mine, both take the disk state with RELOAD`() {
        assertNull(ConflictBanner.of(tab))
        val modified = ConflictBanner.of(tab.copy(conflict = ExternalChangeKind.MODIFIED))!!
        assertEquals(
            listOf("Reload" to ConflictResolution.RELOAD, "Keep mine" to ConflictResolution.KEEP_MINE),
            modified.actions.map { it.label to it.resolution },
        )
        val deleted = ConflictBanner.of(tab.copy(conflict = ExternalChangeKind.DELETED))!!
        assertEquals(
            listOf("Close" to ConflictResolution.RELOAD, "Keep mine" to ConflictResolution.KEEP_MINE),
            deleted.actions.map { it.label to it.resolution },
        )
        assertEquals(true, deleted.message.contains("deleted"))
    }
}
