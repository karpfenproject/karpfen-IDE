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

import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Properties

/**
 * The karpfen-runtime sources bundled with the IDE as the Docker build context of its runtime image, and what the
 * build recorded about them.
 *
 * @property contentHash the SHA-256 of the archive, lower-case hex; it names and labels the image built from it.
 * @property runtimeCommit the runtime commit the sources come from, with `-dirty` if they differ from it.
 * @property fileCount the number of files in the archive.
 * @param openArchive opens the gzip-compressed tar archive of the sources; the caller closes the stream.
 */
internal class RuntimeSources(
    val contentHash: String,
    val runtimeCommit: String,
    val fileCount: Int,
    private val openArchive: () -> InputStream,
) {
    /** The gzip-compressed tar archive of the sources, a Docker build context; the caller closes it. */
    fun open(): InputStream = openArchive()
}

/** What [BundledRuntimeSources.read] found. */
internal sealed interface SourcesLookup {
    /**
     * The sources are bundled.
     *
     * @property sources the sources.
     */
    data class Found(val sources: RuntimeSources) : SourcesLookup

    /**
     * The sources are missing or unreadable.
     *
     * @property reason what is wrong, in words for the user.
     */
    data class Missing(val reason: String) : SourcesLookup
}

/**
 * Reads the runtime sources that the build bundles as resources of this module: the archive [ARCHIVE] and its
 * properties [PROPERTIES], both in this package, as the build task `bundleRuntimeSources` writes them.
 *
 * @param resource opens the resource of a name in this package, or answers null if there is none.
 */
internal class BundledRuntimeSources(
    private val resource: (String) -> InputStream? = { name -> BundledRuntimeSources::class.java.getResourceAsStream(name) },
) {
    /** The bundled sources, or why there are none. */
    fun read(): SourcesLookup {
        val properties = resource(PROPERTIES)?.use { stream -> Properties().apply { load(stream) } }
            ?: return SourcesLookup.Missing("the runtime sources bundled with the IDE are missing ($PROPERTIES)")
        resource(ARCHIVE)?.close() ?: return SourcesLookup.Missing("the runtime sources bundled with the IDE are missing ($ARCHIVE)")
        val contentHash = properties.getProperty(CONTENT_HASH).orEmpty()
        val runtimeCommit = properties.getProperty(RUNTIME_COMMIT).orEmpty()
        val fileCount = properties.getProperty(FILE_COUNT)?.toIntOrNull()
        if (!SHA_256.matches(contentHash) || runtimeCommit.isBlank() || fileCount == null) {
            return SourcesLookup.Missing("the description of the runtime sources bundled with the IDE is unreadable ($PROPERTIES)")
        }
        return SourcesLookup.Found(
            RuntimeSources(contentHash, runtimeCommit, fileCount) {
                resource(ARCHIVE) ?: throw FileNotFoundException("$ARCHIVE is no longer bundled")
            },
        )
    }

    internal companion object {
        /** The archive of the sources. */
        const val ARCHIVE = "runtime-sources.tar.gz"

        /** The properties of the archive. */
        const val PROPERTIES = "runtime-sources.properties"

        /** The property of the archive's SHA-256. */
        const val CONTENT_HASH = "contentHash"

        /** The property of the runtime commit. */
        const val RUNTIME_COMMIT = "runtimeCommit"

        /** The property of the number of files. */
        const val FILE_COUNT = "fileCount"

        /** A SHA-256 in lower-case hex. */
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
