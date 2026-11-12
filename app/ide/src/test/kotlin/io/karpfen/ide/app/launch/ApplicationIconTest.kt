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
package io.karpfen.ide.app.launch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests that the application's icon is among the resources and usable as an icon. */
class ApplicationIconTest {

    @Test
    fun `the icon is a square image that is large enough for a Dock`() {
        val icon = assertNotNull(ApplicationIcon.load(), "no image at ${ApplicationIcon.RESOURCE}")

        assertEquals(icon.width, icon.height, "the icon is not square")
        assertTrue(icon.width >= 512, "the icon is only ${icon.width} px wide")
        assertNotNull(ApplicationIcon.painter())
    }

    @Test
    fun `a headless JVM is left alone`() {
        ApplicationIcon.showInTaskbar()
        ApplicationIcon.showInTaskbar(null)
    }
}
