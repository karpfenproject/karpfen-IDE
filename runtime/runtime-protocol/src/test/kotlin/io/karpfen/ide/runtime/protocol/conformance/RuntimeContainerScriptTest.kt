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
package io.karpfen.ide.runtime.protocol.conformance

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Covers the health wait of `runtime/live-runtime.sh`, the script that runs the runtime in Docker for the live tests,
 * on a virtual clock: [ScriptSandbox] runs the script's `up` command with Docker, git, curl, `sleep` and the clock
 * `date +%s` replaced by shell functions, against a runtime whose port accepts connections but never answers, so every
 * health probe lasts as long as its `--max-time` lets it. The wait gives up once its timeout has passed, however long
 * each probe takes, probes about once a second for at most five seconds each, and cuts the last probe to the time that
 * is left.
 */
class RuntimeContainerScriptTest {

    @Test
    fun `the health wait gives up after its default timeout of 180 seconds, not after 180 rounds`(@TempDir directory: Path) {
        val run = ScriptSandbox(directory).up(healthTimeoutSeconds = null)

        assertEquals(1, run.exitStatus, run.transcript)
        assertContains(run.transcript, "did not answer ${ScriptSandbox.URL}/health within 180 s")
        assertEquals(180, run.elapsedSeconds, "virtual seconds from the start of the wait until the script gave up")
        assertTrue(run.probes.size > 1, "the wait probes more than once: ${run.probes}")
        assertEquals(emptyList(), run.probes.filter { it.endSecond > 180 }, "probes that outlast the timeout")
    }

    @Test
    fun `the health wait probes for at most five seconds a time and cuts the last probe to the time left`(@TempDir directory: Path) {
        val run = ScriptSandbox(directory).up(healthTimeoutSeconds = 8)

        assertEquals(1, run.exitStatus, run.transcript)
        assertContains(run.transcript, "did not answer ${ScriptSandbox.URL}/health within 8 s")
        assertEquals(listOf(Probe(startSecond = 0, maxTimeSeconds = 5), Probe(startSecond = 6, maxTimeSeconds = 2)), run.probes)
        assertEquals(8, run.elapsedSeconds, "virtual seconds from the start of the wait until the script gave up")
    }

    /**
     * One health probe the script made: it began [startSecond] virtual seconds after the wait began and curl allowed it
     * [maxTimeSeconds], all of which it took, since the runtime never answers.
     */
    private data class Probe(val startSecond: Long, val maxTimeSeconds: Long) {
        /** The virtual second at which the probe ended. */
        val endSecond: Long get() = startSecond + maxTimeSeconds
    }

    /**
     * What one run of the script left: its [exitStatus], its standard output and error in [transcript], the [probes] it
     * made, and the virtual seconds that passed from the start of the health wait until it ended, [elapsedSeconds].
     */
    private class ScriptRun(val exitStatus: Int, val transcript: String, val probes: List<Probe>, val elapsedSeconds: Long)

    /**
     * A throwaway copy of the repository's layout in [directory] in which the script runs without Docker: the script
     * copied to `repository/runtime/`, so that its run folders stay in the sandbox, a runtime checkout with nothing but
     * the files the script checks for, and shell functions that stand in for Docker, git, curl, `sleep` and
     * `date +%s`, defined before the script is sourced. The functions keep the virtual clock in a file, which `sleep`
     * and every probe advance, and log each probe. `DOCKER_HOST` names a socket nobody listens on and the search path
     * holds only the system's own directories, so a call that slipped past the functions could not reach a Docker
     * engine.
     */
    private class ScriptSandbox(private val directory: Path) {

        /** The file that holds the virtual clock in seconds. */
        private val clock: Path = directory.resolve("clock")

        /** The file to which every probe appends its start on the virtual clock and its `--max-time`. */
        private val probeLog: Path = directory.resolve("probes")

        /**
         * Runs `up` with the health timeout [healthTimeoutSeconds] in `KARPFEN_LIVE_HEALTH_TIMEOUT_S`, or with the
         * script's default if it is null, and answers what the run left.
         */
        fun up(healthTimeoutSeconds: Int?): ScriptRun {
            val script = System.getProperty(SCRIPT_PROPERTY)
                ?: fail("the system property $SCRIPT_PROPERTY is not set: runtime-protocol's test task sets it to the script")
            assumeTrue(Files.isExecutable(Paths.get(BASH)), "needs $BASH, the shell of the script")
            val copy = directory.resolve("repository/runtime/live-runtime.sh")
            Files.createDirectories(copy.parent)
            Files.copy(Paths.get(script), copy)
            val runtime = directory.resolve("karpfen-runtime")
            Files.createDirectories(runtime.resolve("karpfen-dsl-tools"))
            Files.writeString(runtime.resolve("Dockerfile"), "")
            Files.writeString(runtime.resolve("karpfen-dsl-tools/settings.gradle"), "")
            val stubs = Files.writeString(directory.resolve("stubs.bash"), stubs())
            Files.writeString(clock, "$CLOCK_START")
            Files.writeString(probeLog, "")
            val transcript = directory.resolve("transcript")
            val builder = ProcessBuilder(BASH, "-c", SOURCE_STUBS_AND_SCRIPT, "sandbox", stubs.toString(), copy.toString(), "up")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile())
            builder.environment().apply {
                clear()
                put("PATH", "/usr/bin:/bin")
                put("DOCKER_HOST", "unix://${directory.resolve("no-docker.sock")}")
                put("KARPFEN_RUNTIME_DIR", runtime.toString())
                healthTimeoutSeconds?.let { put("KARPFEN_LIVE_HEALTH_TIMEOUT_S", "$it") }
            }
            val process = builder.start()
            if (!process.waitFor(HANG_GUARD_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                fail("the script did not end; its output so far:\n${Files.readString(transcript)}")
            }
            return ScriptRun(
                exitStatus = process.exitValue(),
                transcript = Files.readString(transcript),
                probes = Files.readAllLines(probeLog).filter { it.isNotBlank() }.map { line ->
                    val (start, maxTime) = line.trim().split(' ')
                    Probe(startSecond = start.toLong() - CLOCK_START, maxTimeSeconds = maxTime.toLong())
                },
                elapsedSeconds = Files.readString(clock).trim().toLong() - CLOCK_START,
            )
        }

        /**
         * The shell functions that stand in for the outside commands: `date +%s` reads the virtual clock and any other
         * `date` is the real one; `sleep` advances the clock; `curl` logs the probe, advances the clock by its whole
         * `--max-time` and fails as curl does on a timeout; git names a clean commit; Docker answers only what the way
         * to the health wait reads (the image's label, the published port and the running container) and does nothing
         * else.
         */
        private fun stubs(): String = """
            advance() { echo "$(( $(cat '$clock') + $1 ))" >'$clock'; }
            date() { if [ "$*" = +%s ]; then cat '$clock'; else command date "$@"; fi; }
            sleep() { advance "$1"; }
            curl() {
                while [ "$1" != --max-time ]; do shift; done
                echo "$(cat '$clock') $2" >>'$probeLog'
                advance "$2"
                return 28
            }
            git() { case "$*" in *rev-parse*) echo 0123456789ab ;; esac; }
            docker() {
                case "$*" in
                    "image inspect"*) echo true ;;
                    port\ *) echo 127.0.0.1:$PORT ;;
                    inspect\ *) echo running ;;
                esac
            }
        """.trimIndent()

        companion object {
            /** The system property through which runtime-protocol's test task names `runtime/live-runtime.sh`. */
            const val SCRIPT_PROPERTY = "karpfen.runtime.script"

            /** The shell of the script. */
            const val BASH = "/bin/bash"

            /** The command line that sources the stubs and then the script with its command, given as arguments. */
            const val SOURCE_STUBS_AND_SCRIPT = ". \"$1\" && . \"$2\" \"$3\""

            /** The port that the stand-in for Docker publishes. */
            const val PORT = 49152

            /** The URL of the runtime that the script derives from [PORT]. */
            const val URL = "http://127.0.0.1:$PORT"

            /** The virtual clock's reading when the script starts, in seconds. */
            const val CLOCK_START = 1_000_000L

            /** The bound after which a run that never ends counts as hanging; it is no assertion on time. */
            const val HANG_GUARD_MINUTES = 5L
        }
    }
}
