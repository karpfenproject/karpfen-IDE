package io.karpfen.ide.tools.cli.validate

import java.io.PrintStream

/**
 * Keeps the CLI's own output clean while dsl-tools prints (spec §9): [silenced] redirects `System.out` and `System.err`
 * to a sink for the duration of a block (dsl-tools `println`s while converting, and its ANTLR console listener writes
 * syntax errors to `System.err`, see `DslConverter`); the CLI prints its report through [output] and its own error
 * messages through [errorOutput], the original streams captured at construction.
 */
public class StandardOutputGuard(
    public val output: PrintStream = System.out,
    public val errorOutput: PrintStream = System.err,
) {
    /**
     * Runs [block] with `System.out` and `System.err` redirected; restores both afterwards, also on exceptions and
     * cancellation. Suspends instead of blocking, so it can wrap a whole workspace run (also under `runTest`). Not
     * reentrant.
     */
    public suspend fun <T> silenced(block: suspend () -> T): T = TODO("WP-APP: redirect System.out and System.err")
}
