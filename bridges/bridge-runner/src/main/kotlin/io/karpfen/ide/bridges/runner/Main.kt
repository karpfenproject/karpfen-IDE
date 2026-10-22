package io.karpfen.ide.bridges.runner

/** One-line usage of the headless bridge runner (phase-0 stub: no bridge is started yet). */
public const val USAGE: String =
    "Usage: karpfen-bridge-runner <bridge-config.kbridge.json>  (phase-0 stub, no bridges are started)"

/**
 * Entry point of the headless bridge runner. Bridges are headless-first: plain Kotlin components with a lifecycle that
 * run from this command line on a robot or edge box without the IDE, while the IDE configures, hosts and monitors them
 * during development.
 */
public fun main() {
    println(USAGE)
}
