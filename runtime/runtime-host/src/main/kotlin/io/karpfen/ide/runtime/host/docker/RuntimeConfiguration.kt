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
package io.karpfen.ide.runtime.host.docker

/**
 * The configuration file of the runtime in the IDE's container. It is `application.conf` in the runtime's working
 * folder [FOLDER]. It makes the server listen on all interfaces of the container at port 8080 and turns the WebSocket
 * on. It logs to the console at `INFO`. It sets the engine's defaults of 50 ms per tick and 3,000 ms of event time to
 * live, and it turns engine tracing off. The host copies the file into the container before the start, as the
 * one-entry tar archive that [tar] builds.
 */
internal object RuntimeConfiguration {
    /** The folder of the container the file goes to, the runtime's working folder. */
    const val FOLDER = "/app"

    /** The file name the runtime reads its configuration from. */
    const val FILE_NAME = "application.conf"

    /** The content of the file. */
    val text: String = """
        server {
          host = "0.0.0.0"
          port = 8080
        }
        websocket {
          queueTimeoutMs = 1000
          enabled = true
        }
        logging {
          level = "INFO"
          consoleOutput = true
        }
        engine {
          defaultTickDelayMs = 50
          defaultEventTtlMs = 3000
          eventConsumptionOnFire = true
        }
        engineTracing {
          tracingEnabled = false
          tracingLogDirectory = "/app/logs"
          tracingConsoleOutput = false
          simpleTrace = true
        }
    """.trimIndent() + "\n"

    /** The file as a tar archive of one entry, [FILE_NAME] with the mode 0644, owned by root. */
    fun tar(): ByteArray {
        val content = text.toByteArray(Charsets.UTF_8)
        val header = ByteArray(BLOCK)
        header.put(0, FILE_NAME)
        header.put(100, octal(FILE_MODE, 8))
        header.put(108, octal(0, 8))
        header.put(116, octal(0, 8))
        header.put(124, octal(content.size.toLong(), 12))
        header.put(136, octal(0, 12))
        header.put(148, " ".repeat(8))
        header[156] = '0'.code.toByte()
        header.put(257, "ustar\u0000")
        header.put(263, "00")
        header.put(265, OWNER)
        header.put(297, OWNER)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        header.put(148, "%06o".format(checksum) + "\u0000 ")
        val padding = (BLOCK - content.size % BLOCK) % BLOCK
        return header + content + ByteArray(padding) + ByteArray(2 * BLOCK)
    }

    /** The size of a tar block. */
    private const val BLOCK = 512

    /** The user and group name of the entry. */
    private const val OWNER = "root"

    /** The mode of the file, 0644. */
    private val FILE_MODE = "644".toLong(8)

    /** [value] as zero-padded octal digits filling a field of [length] bytes, the last of them a NUL. */
    private fun octal(value: Long, length: Int): String = java.lang.Long.toOctalString(value).padStart(length - 1, '0') + "\u0000"

    /** Copies the ASCII [text] into this header at [offset]. */
    private fun ByteArray.put(offset: Int, text: String) {
        text.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }
}
