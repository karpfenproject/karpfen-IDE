package io.karpfen.ide.tools.cli.validate

import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the CLI's own output clean while dsl-tools prints: [silenced] redirects `System.out` and `System.err` to a sink
 * for the duration of a block (dsl-tools `println`s while converting, and its ANTLR console listener writes syntax
 * errors to `System.err`, see `DslConverter`); the CLI prints its report through [output] and its own error messages
 * through [errorOutput], the original streams captured at construction.
 *
 * The redirection is process-wide (`System.setOut`/`System.setErr`), so it also catches output of threads other than
 * the caller's (the analysis dispatcher). The discarded bytes are only counted (logged at DEBUG afterwards).
 *
 * The default [output] and [errorOutput] encode UTF-8 onto the process streams, whatever the console's encoding
 * (`stdout.encoding`, US-ASCII under `LANG=C` in containers and minimal CI runners), so paths and messages with
 * non-ASCII characters keep naming their files instead of turning into `?`.
 */
public class StandardOutputGuard(
    public val output: PrintStream = utf8(System.out),
    public val errorOutput: PrintStream = utf8(System.err),
) {
    private val active = AtomicBoolean(false)

    /**
     * Runs [block] with `System.out` and `System.err` redirected; restores both afterwards, also on exceptions and
     * cancellation. Suspends instead of blocking, so it can wrap a whole workspace run (also under `runTest`). Not
     * reentrant.
     *
     * @throws IllegalStateException if called while another [silenced] block of this guard runs.
     */
    public suspend fun <T> silenced(block: suspend () -> T): T {
        check(active.compareAndSet(false, true)) { "StandardOutputGuard.silenced is not reentrant" }
        val previousOut = System.out
        val previousErr = System.err
        val sink = DiscardingStream()
        val sinkStream = PrintStream(sink, true, Charsets.UTF_8)
        System.setOut(sinkStream)
        System.setErr(sinkStream)
        try {
            return block()
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            active.set(false)
            if (sink.count > 0) log.debug("Discarded {} bytes of console output written while silenced", sink.count)
        }
    }

    /** Discards everything written to it and counts the bytes (thread-safe). */
    private class DiscardingStream : OutputStream() {
        private val written = AtomicLong()

        /** The number of bytes discarded so far. */
        val count: Long get() = written.get()

        override fun write(b: Int) {
            written.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written.addAndGet(len.toLong())
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(StandardOutputGuard::class.java)

        /** A stream encoding UTF-8 onto [stream] (bytes pass a `PrintStream` unchanged, whatever its own encoding). */
        fun utf8(stream: PrintStream): PrintStream = PrintStream(stream, true, Charsets.UTF_8)
    }
}
