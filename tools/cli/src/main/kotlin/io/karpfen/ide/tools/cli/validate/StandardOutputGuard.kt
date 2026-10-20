package io.karpfen.ide.tools.cli.validate

import java.io.PrintStream

/**
 * Keeps the CLI's own output clean while dsl-tools prints (spec §9): [silenced] redirects `System.out` to a sink for
 * the duration of a block; the CLI prints through [output], the original stream captured at construction.
 */
public class StandardOutputGuard(public val output: PrintStream = System.out) {
    /** Runs [block] with `System.out` redirected; restores it afterwards, also on exceptions. Not reentrant. */
    public fun <T> silenced(block: () -> T): T = TODO("WP-APP: redirect System.out")
}
