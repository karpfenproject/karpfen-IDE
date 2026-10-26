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
package io.karpfen.ide.ui.foundation.keys

import kotlin.test.Test
import kotlin.test.assertEquals

/** The key values: platform detection from `os.name` and the physical stroke of a shortcut on each platform. */
class KeysContractTest {

    @Test
    fun `platforms are detected from os name`() {
        assertEquals(HostPlatform.MAC_OS, HostPlatform.fromOsName("Mac OS X"))
        assertEquals(HostPlatform.WINDOWS, HostPlatform.fromOsName("Windows 11"))
        assertEquals(HostPlatform.LINUX, HostPlatform.fromOsName("Linux"))
        assertEquals(HostPlatform.LINUX, HostPlatform.fromOsName("FreeBSD"))
    }

    @Test
    fun `the primary modifier is Cmd on macOS and Ctrl elsewhere`() {
        val save = KeyShortcut(KeyCode.S, primary = true, shift = true)

        assertEquals(KeyStroke(KeyCode.S, setOf(KeyModifier.META, KeyModifier.SHIFT)), save.strokeFor(HostPlatform.MAC_OS))
        assertEquals(KeyStroke(KeyCode.S, setOf(KeyModifier.CTRL, KeyModifier.SHIFT)), save.strokeFor(HostPlatform.LINUX))
        assertEquals(
            KeyStroke(KeyCode.SPACE, setOf(KeyModifier.CTRL)),
            KeyShortcut(KeyCode.SPACE, ctrl = true).strokeFor(HostPlatform.MAC_OS),
        )
    }
}
