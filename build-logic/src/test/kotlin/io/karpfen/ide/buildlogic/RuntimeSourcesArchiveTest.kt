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
package io.karpfen.ide.buildlogic

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Covers [RuntimeSourcesArchive] against temporary git repositories: the archive holds exactly the tracked files of the
 * working tree, sorted, with fixed times, owner and modes, packing twice gives identical bytes, the properties record
 * the archive's hash, the commit and the file count, and a checkout that is missing fails with the command that checks
 * it out.
 */
class RuntimeSourcesArchiveTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `the archive holds the tracked files of the working tree, sorted, with fixed times, owner and modes`() {
        val checkout = repository()

        RuntimeSourcesArchive.pack(checkout, directory.resolve("out"))

        val entries = entriesOf(directory.resolve("out/${RuntimeSourcesArchive.ARCHIVE_NAME}"))
        assertEquals(listOf("Dockerfile", "a.txt", "gradlew", "src/Main.kt", "src/main/b.txt"), entries.map { it.path })
        assertEquals(listOf("644", "644", "755", "644", "644"), entries.map { it.mode })
        assertTrue(entries.all { it.modificationTime == RuntimeSourcesArchive.MODIFICATION_TIME }, "$entries")
        assertTrue(entries.all { it.owner == "root" && it.group == "root" }, "$entries")
        assertEquals("fun main() = Unit, edited\n", entries.single { it.path == "src/Main.kt" }.text)
    }

    @Test
    fun `packing twice gives identical bytes`() {
        val checkout = repository()

        RuntimeSourcesArchive.pack(checkout, directory.resolve("first"))
        RuntimeSourcesArchive.pack(checkout, directory.resolve("second"))

        assertArrayEquals(
            directory.resolve("first/${RuntimeSourcesArchive.ARCHIVE_NAME}").readBytes(),
            directory.resolve("second/${RuntimeSourcesArchive.ARCHIVE_NAME}").readBytes(),
        )
        assertEquals(
            directory.resolve("first/${RuntimeSourcesArchive.PROPERTIES_NAME}").readText(),
            directory.resolve("second/${RuntimeSourcesArchive.PROPERTIES_NAME}").readText(),
        )
    }

    @Test
    fun `the properties record the archive's hash, the commit with its dirty mark and the file count`() {
        val checkout = repository()
        val head = git(checkout, "rev-parse", "HEAD").trim()

        val summary = RuntimeSourcesArchive.pack(checkout, directory.resolve("dirty"))
        git(checkout, "commit", "--quiet", "--all", "--message", "Edit")
        val clean = RuntimeSourcesArchive.pack(checkout, directory.resolve("clean"))

        val properties = Properties().apply { directory.resolve("dirty/${RuntimeSourcesArchive.PROPERTIES_NAME}").reader().use(::load) }
        val archive = directory.resolve("dirty/${RuntimeSourcesArchive.ARCHIVE_NAME}").readBytes()
        assertEquals(sha256(archive), properties.getProperty("contentHash"))
        assertEquals("$head-dirty", properties.getProperty("runtimeCommit"))
        assertEquals("5", properties.getProperty("fileCount"))
        assertEquals(RuntimeSourcesArchive.Summary(sha256(archive), "$head-dirty", 5), summary)
        assertEquals(git(checkout, "rev-parse", "HEAD").trim(), clean.runtimeCommit)
        assertEquals(summary.contentHash, clean.contentHash)
    }

    @Test
    fun `a checkout that is missing fails with the command that checks it out`() {
        val superproject = repository()
        val uninitialized = superproject.resolve("karpfen-runtime").apply { mkdirs() }

        val failure = assertThrows(GradleException::class.java) {
            RuntimeSourcesArchive.pack(uninitialized, directory.resolve("out"))
        }

        assertTrue(RuntimeSourcesArchive.REMEDY in failure.message.orEmpty(), failure.message)
        assertEquals(emptyList<String>(), RuntimeSourcesArchive.trackedPathsOrEmpty(uninitialized))
    }

    /**
     * A git repository with tracked files, one of them executable and one edited after the commit, an untracked file
     * and an ignored build folder, which the archive must leave out.
     */
    private fun repository(): File {
        val root = directory.resolve("repository")
        root.mkdirs()
        git(root, "init", "--quiet")
        git(root, "config", "user.email", "test@example.org")
        git(root, "config", "user.name", "Test")
        git(root, "config", "core.autocrlf", "false")
        git(root, "config", "commit.gpgsign", "false")
        root.resolve(".gitignore").writeText("build/\n")
        root.resolve("Dockerfile").writeText("FROM scratch\n")
        root.resolve("a.txt").writeText("a\n")
        root.resolve("gradlew").apply { writeText("#!/bin/sh\n") }.setExecutable(true)
        root.resolve("src/main").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = Unit\n")
        root.resolve("src/main/b.txt").writeText("b\n")
        git(root, "add", "Dockerfile", "a.txt", "gradlew", "src")
        git(root, "update-index", "--chmod=+x", "gradlew")
        git(root, "commit", "--quiet", "--message", "Initial")
        root.resolve("src/Main.kt").writeText("fun main() = Unit, edited\n")
        root.resolve("untracked.txt").writeText("untracked\n")
        root.resolve("build").mkdirs()
        root.resolve("build/output.txt").writeText("output\n")
        return root
    }

    /**
     * One file entry of an archive.
     *
     * @property path the path, from the prefix and name fields.
     * @property mode the permission bits in octal.
     * @property modificationTime the modification time in seconds since the epoch.
     * @property owner the user name.
     * @property group the group name.
     * @property text the content as text.
     */
    private data class Entry(
        val path: String,
        val mode: String,
        val modificationTime: Long,
        val owner: String,
        val group: String,
        val text: String,
    )

    /** The entries of the gzip-compressed ustar [archive], read field by field. */
    private fun entriesOf(archive: File): List<Entry> {
        val bytes = GZIPInputStream(archive.inputStream()).use { it.readBytes() }
        val entries = ArrayList<Entry>()
        var offset = 0
        while (bytes.copyOfRange(offset, offset + BLOCK).any { it != 0.toByte() }) {
            val header = bytes.copyOfRange(offset, offset + BLOCK)
            val size = field(header, 124, 12).toLong(8)
            val prefix = field(header, 345, 155)
            val name = field(header, 0, 100)
            entries += Entry(
                path = if (prefix.isEmpty()) name else "$prefix/$name",
                mode = field(header, 100, 8).trimStart('0'),
                modificationTime = field(header, 136, 12).toLong(8),
                owner = field(header, 265, 32),
                group = field(header, 297, 32),
                text = String(bytes, offset + BLOCK, size.toInt(), Charsets.UTF_8),
            )
            offset += BLOCK + ((size + BLOCK - 1) / BLOCK * BLOCK).toInt()
        }
        return entries
    }

    /** The text of the header field at [offset] of [length] bytes, up to its first NUL. */
    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000').trim()

    /** The standard output of git run in [directory] with [arguments]; fails the test if git fails. */
    private fun git(directory: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git ${arguments.toList()} did not finish")
        assertEquals(0, process.exitValue(), "git ${arguments.toList()} failed: $output")
        return output
    }

    /** The SHA-256 of [bytes], lower-case hex. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** The size of a tar block. */
        const val BLOCK = 512
    }
}
