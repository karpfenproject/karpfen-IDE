package io.karpfen.ide.tools.cli

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** One-line usage of the karpfen command-line tool. */
public const val USAGE: String = "Usage: karpfen validate <dir> [--format text|json] [--verbose]"

/**
 * Entry point of the karpfen command-line tool (start script `karpfen`): runs the command line and exits with its
 * status, see [ExitCodes] (0 clean, 1 if `validate` found an ERROR, 2 for invalid arguments, 3 on an internal failure).
 */
public fun main(args: Array<String>) {
    val status = runBlocking { KarpfenCli().execute(args.asList()) }
    exitProcess(status)
}
