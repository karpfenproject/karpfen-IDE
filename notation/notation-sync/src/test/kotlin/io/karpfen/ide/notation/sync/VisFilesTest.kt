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
package io.karpfen.ide.notation.sync

import io.karpfen.ide.notation.diagram.layout.DiagramPoint
import io.karpfen.ide.notation.diagram.layout.VisLayout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests where the companion file of a model lives and that a layout survives being written and read. */
class VisFilesTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the companion file sits next to the model and has its name with vis appended`() {
        assertEquals(dir.resolve("lamp.kstates.vis"), VisFiles.pathFor(dir.resolve("lamp.kstates")))
    }

    @Test
    fun `a layout is written and read back`() {
        val file = VisFiles.pathFor(dir.resolve("lamp.kstates"))
        val layout = VisLayout(states = mapOf("off" to DiagramPoint(120f, 40f)))

        assertTrue(VisFiles.write(file, layout))

        assertEquals(layout, VisFiles.read(file))
    }

    @Test
    fun `a missing or broken companion file means no layout by hand`() {
        assertNull(VisFiles.read(dir.resolve("none.kstates.vis")))
        assertNull(VisFiles.read(Files.writeString(dir.resolve("broken.kstates.vis"), "{ not json")))
        assertFalse(VisFiles.write(dir.resolve("no/such/folder/x.vis"), VisLayout()))
    }
}
