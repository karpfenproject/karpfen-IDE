package io.karpfen.ide.tools.cli

import kotlin.test.Test
import kotlin.test.assertTrue

/** The usage line of the command-line tool. */
class MainTest {
    @Test
    fun `usage is a single line`() {
        assertTrue(USAGE.startsWith("Usage: karpfen "))
        assertTrue('\n' !in USAGE)
    }
}
