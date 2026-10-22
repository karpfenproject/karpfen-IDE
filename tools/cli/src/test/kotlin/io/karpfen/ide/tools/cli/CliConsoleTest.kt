package io.karpfen.ide.tools.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** [CliConsole]: the process streams it captures at construction, written in UTF-8 whatever the console's encoding. */
class CliConsoleTest {

    @Test
    fun `the default streams write to the process streams at construction`() {
        ProcessStreams().use { streams ->
            val console = CliConsole()
            System.setOut(PrintStream(ByteArrayOutputStream()))
            System.setErr(PrintStream(ByteArrayOutputStream()))

            console.output.println("report")
            console.errorOutput.println("message")

            assertEquals("report\n", streams.stdout())
            assertEquals("message\n", streams.stderr())
        }
    }

    @Test
    fun `the default streams write UTF-8 whatever the console's encoding, so non-ASCII paths survive LANG=C`() {
        ProcessStreams(encoding = Charsets.US_ASCII).use { streams ->
            val console = CliConsole()

            console.output.println("Übung/größe.kmeta")
            console.errorOutput.println("karpfen: not a directory: Übung")

            assertEquals("Übung/größe.kmeta\n", streams.stdout())
            assertEquals("karpfen: not a directory: Übung\n", streams.stderr())
        }
    }
}
