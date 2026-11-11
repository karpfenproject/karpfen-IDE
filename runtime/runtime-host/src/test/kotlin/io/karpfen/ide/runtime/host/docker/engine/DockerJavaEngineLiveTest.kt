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
package io.karpfen.ide.runtime.host.docker.engine

import io.karpfen.ide.runtime.host.TarEntries
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs [DockerJavaEngine] against the local Docker engine with a tiny image built `FROM scratch`, so nothing is pulled.
 * It covers building, listing, creating, copying into, inspecting and removing, and a failing build. Every image and
 * container it makes carries the label `io.karpfen.ide.test=true` and is removed in `finally`. The test runs only in
 * the opt-in task `liveDockerTest`.
 */
@Tag("live-docker")
class DockerJavaEngineLiveTest {

    /** The unique part of this run's names. */
    private val run = UUID.randomUUID().toString().take(8)

    /** The labels of this run's image and container. */
    private val labels = mapOf(TEST_LABEL to "true", "$TEST_LABEL.run" to run)

    @Test
    fun `the engine builds, lists, creates, copies into, inspects and removes`() = runBlocking {
        val engine = DockerJavaEngine(localEndpoint())
        val tag = "karpfen-ide-test/engine:$run"
        try {
            engine.ping()
            assertFalse(engine.imageExists(tag))

            val output = ArrayList<String>()
            engine.buildImage(context(dockerfile = "FROM scratch\nCOPY marker.txt /marker.txt\nCMD [\"/marker\"]\n"), tag, labels) { output += it }

            assertTrue(output.any { it.startsWith("Step 1/") }, "$output")
            assertTrue(engine.imageExists(tag))
            assertEquals(1, engine.listImages(labels).size)

            val id = engine.createContainer(ContainerSpec(tag, "karpfen-ide-test-engine-$run", labels))
            engine.copyToContainer(id, "/", tar("copied.txt", "copied\n"))
            assertEquals(ContainerInspection(running = false, exitCode = 0, hostPort = null), engine.inspectContainer(id))
            assertEquals(listOf(id), engine.listContainers(labels, includeStopped = true).map { it.id })
            assertEquals(emptyList(), engine.listContainers(labels, includeStopped = false))

            engine.removeContainer(id)
            engine.removeContainer(id)
            assertEquals(emptyList(), engine.listContainers(labels, includeStopped = true))
        } finally {
            cleanUp(engine)
        }
    }

    @Test
    fun `a failing build reports its error`() = runBlocking {
        val engine = DockerJavaEngine(localEndpoint())
        try {
            val failure = assertFailsWith<DockerEngineException> {
                engine.buildImage(context(dockerfile = "FROM scratch\nCOPY missing.txt /missing.txt\n"), "karpfen-ide-test/failing:$run", labels) { }
            }
            assertTrue("missing.txt" in failure.message.orEmpty(), failure.message)
        } finally {
            cleanUp(engine)
        }
    }

    /** Removes this run's containers and images and closes [engine]. */
    private suspend fun cleanUp(engine: DockerJavaEngine) {
        engine.listContainers(labels, includeStopped = true).forEach { engine.removeContainer(it.id) }
        engine.listImages(labels).forEach { engine.removeImage(it.id) }
        engine.close()
    }

    /** A build context of [dockerfile] and the file `marker.txt`. */
    private fun context(dockerfile: String): InputStream {
        val output = ByteArrayOutputStream()
        listOf("Dockerfile" to dockerfile, "marker.txt" to "marker\n").forEach { (name, text) -> output.write(tar(name, text).dropLast(1024).toByteArray()) }
        output.write(ByteArray(1024))
        return output.toByteArray().also { assertEquals(2, TarEntries.read(it).size) }.inputStream()
    }

    /** A tar archive with the one file [name] of [text]. */
    private fun tar(name: String, text: String): ByteArray {
        val content = text.toByteArray()
        val header = ByteArray(512)
        fun put(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        put(0, name)
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, java.lang.Long.toOctalString(content.size.toLong()).padStart(11, '0') + "\u0000")
        put(136, "00000000000\u0000")
        put(148, "        ")
        put(156, "0")
        put(257, "ustar\u000000")
        put(148, "%06o".format(header.sumOf { it.toInt() and 0xFF }) + "\u0000 ")
        return header + content + ByteArray((512 - content.size % 512) % 512) + ByteArray(1024)
    }

    /** The endpoint of the local engine. Fails the test if the engine is not local. */
    private fun localEndpoint(): String {
        val endpoint = DockerEndpointResolver().resolve()
        assertTrue(endpoint is DockerEndpoint.Local, "the Docker engine is not local: ${endpoint.uri}")
        return endpoint.uri
    }

    private companion object {
        /** The label of every Docker resource the live tests make. */
        const val TEST_LABEL = "io.karpfen.ide.test"
    }
}
