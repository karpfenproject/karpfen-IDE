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
package io.karpfen.ide.runtime.host.bundled

import io.karpfen.ide.runtime.host.TarEntries
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers [BundledRuntimeSources]: the build bundles the tracked runtime sources with their properties, which describe
 * the archive exactly, and missing or unreadable resources are reported as missing sources.
 */
class BundledRuntimeSourcesTest {

    @Test
    fun `the bundled archive holds the runtime's build context and its properties describe it`() {
        val sources = assertIs<SourcesLookup.Found>(BundledRuntimeSources().read()).sources

        val archive = sources.open().use(InputStream::readBytes)
        val entries = TarEntries.read(GZIPInputStream(archive.inputStream()).use(InputStream::readBytes))

        assertEquals(sha256(archive), sources.contentHash)
        assertEquals(entries.size, sources.fileCount)
        assertTrue(Regex("[0-9a-f]{40}(-dirty)?").matches(sources.runtimeCommit), sources.runtimeCommit)
        val paths = entries.map { it.path }
        assertEquals(paths.sorted(), paths)
        assertTrue("Dockerfile" in paths && "gradlew" in paths && "karpfen-dsl-tools/settings.gradle" in paths, "$paths")
        assertTrue(paths.none { it.startsWith(".git/") || it.startsWith("build/") || "/build/" in it }, "$paths")
        assertEquals("755", entries.single { it.path == "gradlew" }.mode)
        assertEquals("644", entries.single { it.path == "Dockerfile" }.mode)
    }

    @Test
    fun `missing or unreadable resources are missing sources`() {
        val properties = "contentHash=${"a".repeat(64)}\nruntimeCommit=abc\nfileCount=3\n"
        fun lookup(vararg resources: Pair<String, String>): SourcesLookup =
            BundledRuntimeSources { name -> resources.toMap()[name]?.byteInputStream() }.read()

        assertIs<SourcesLookup.Missing>(lookup())
        assertIs<SourcesLookup.Missing>(lookup(BundledRuntimeSources.PROPERTIES to properties))
        assertIs<SourcesLookup.Missing>(lookup(BundledRuntimeSources.ARCHIVE to "archive"))
        assertIs<SourcesLookup.Missing>(
            lookup(BundledRuntimeSources.PROPERTIES to "contentHash=short\n", BundledRuntimeSources.ARCHIVE to "archive"),
        )
        val found = assertIs<SourcesLookup.Found>(
            lookup(BundledRuntimeSources.PROPERTIES to properties, BundledRuntimeSources.ARCHIVE to "archive"),
        ).sources
        assertEquals(listOf("a".repeat(64), "abc", "3"), listOf(found.contentHash, found.runtimeCommit, "${found.fileCount}"))
        assertEquals("archive", found.open().use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** The SHA-256 of [bytes], lower-case hex. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
