package io.karpfen.ide.ui.foundation.keys

import kotlin.test.Test
import kotlin.test.assertEquals

/** Contract test of the complete key values (no phase-1 implementation code). */
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
