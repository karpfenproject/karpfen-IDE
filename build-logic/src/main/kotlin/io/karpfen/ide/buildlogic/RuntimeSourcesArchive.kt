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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * The karpfen-runtime sources that the IDE bundles as the Docker build context of its runtime image: the files git
 * tracks in a checkout of the runtime, those of its nested submodules included, read from the working tree and packed
 * into a deterministic gzip-compressed tar archive, [ARCHIVE_NAME], next to the properties file [PROPERTIES_NAME].
 *
 * The archive holds nothing else, no `.git` and no build output, and it leaves out a tracked file that was deleted from
 * the working tree. Uncommitted edits of tracked files are packed as they are. The entries are sorted by path and share
 * one modification time and one owner. A file has the mode 0644, or 0755 if git records it as executable, such as the
 * Gradle wrapper. Packing the same files twice gives the same bytes. The properties file records the archive's SHA-256
 * as `contentHash`, the checkout's commit as `runtimeCommit` and the number of files as `fileCount`. The commit carries
 * the suffix `-dirty` when the tracked files differ from it.
 */
object RuntimeSourcesArchive {
    /** The file name of the archive. */
    const val ARCHIVE_NAME: String = "runtime-sources.tar.gz"

    /** The file name of the properties. */
    const val PROPERTIES_NAME: String = "runtime-sources.properties"

    /** The command that checks out the runtime and its nested submodule, which the failures name. */
    const val REMEDY: String = "git submodule update --init --recursive"

    /** The modification time of every entry: 2026-01-01T00:00:00Z, in seconds since the epoch. */
    const val MODIFICATION_TIME: Long = 1_767_225_600L

    /** The mode of a file. */
    private val FILE_MODE = "644".toInt(8)

    /** The mode of a file that git records as executable. */
    private val EXECUTABLE_MODE = "755".toInt(8)

    /** The mode git records for an executable file. */
    private const val GIT_EXECUTABLE = "100755"

    /** How long one git command may take. */
    private const val GIT_TIMEOUT_SECONDS = 60L

    /**
     * A file git tracks in the checkout.
     *
     * @property path the path relative to the checkout, with '/' separators.
     * @property executable true if git records the file as executable.
     */
    data class TrackedFile(val path: String, val executable: Boolean)

    /**
     * What [pack] wrote.
     *
     * @property contentHash the SHA-256 of the archive, lower-case hex.
     * @property runtimeCommit the checkout's commit, with `-dirty` when its tracked files differ from it.
     * @property fileCount the number of files in the archive.
     */
    data class Summary(val contentHash: String, val runtimeCommit: String, val fileCount: Int)

    /**
     * The paths of the files git tracks in [checkout], those of its nested submodules included, or an empty list if
     * [checkout] is no checkout or git fails. A task declares its inputs with this list, so it never fails. [pack]
     * checks the checkout itself and reports what is missing.
     */
    fun trackedPathsOrEmpty(checkout: File): List<String> =
        if (isCheckout(checkout)) runCatching { trackedFiles(checkout).map { it.path } }.getOrDefault(emptyList()) else emptyList()

    /**
     * Packs the files git tracks in [checkout] into [ARCHIVE_NAME] and writes [PROPERTIES_NAME] next to it, both in
     * [directory], which is created if needed.
     *
     * @throws GradleException if [checkout] or one of its nested submodules is not checked out, naming [REMEDY], or if
     *   git fails.
     */
    fun pack(checkout: File, directory: File): Summary {
        requireCheckout(checkout)
        val files = trackedFiles(checkout).filter { checkout.resolve(it.path).isFile }.sortedBy { it.path }
        directory.mkdirs()
        val archive = directory.resolve(ARCHIVE_NAME)
        GZIPOutputStream(archive.outputStream().buffered()).use { gzip ->
            val tar = TarArchive(gzip, MODIFICATION_TIME)
            files.forEach { file ->
                tar.add(file.path, checkout.resolve(file.path).readBytes(), if (file.executable) EXECUTABLE_MODE else FILE_MODE)
            }
            tar.finish()
        }
        val summary = Summary(sha256(archive.readBytes()), commit(checkout), files.size)
        directory.resolve(PROPERTIES_NAME).writeText(
            "contentHash=${summary.contentHash}\nruntimeCommit=${summary.runtimeCommit}\nfileCount=${summary.fileCount}\n",
        )
        return summary
    }

    /**
     * Fails unless [checkout] is a checkout of its own, as an initialized submodule is, and every submodule nested in
     * it is initialized. An uninitialized submodule is just an empty directory of its superproject, and
     * `git submodule status` marks it with a leading `-`.
     */
    private fun requireCheckout(checkout: File) {
        if (!isCheckout(checkout)) {
            throw GradleException("the karpfen-runtime sources at $checkout are not checked out; run '$REMEDY'")
        }
        val uninitialized = git(checkout, "submodule", "status", "--recursive").lines().filter { it.startsWith("-") }
        if (uninitialized.isNotEmpty()) {
            throw GradleException(
                "the submodules ${uninitialized.map { it.substringAfter(' ').substringBefore(' ') }} of the karpfen-runtime " +
                    "sources at $checkout are not checked out; run '$REMEDY'",
            )
        }
    }

    /** True if [checkout] is a directory with a git checkout of its own. */
    private fun isCheckout(checkout: File): Boolean = checkout.resolve(".git").exists()

    /** The files git tracks in [checkout] and its nested submodules. */
    private fun trackedFiles(checkout: File): List<TrackedFile> =
        git(checkout, "ls-files", "-z", "--stage", "--recurse-submodules")
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map { record ->
                val (meta, path) = record.split('\t', limit = 2)
                TrackedFile(path, meta.substringBefore(' ') == GIT_EXECUTABLE)
            }

    /** The commit checked out in [checkout], with `-dirty` when its tracked files differ from it. */
    private fun commit(checkout: File): String {
        val head = git(checkout, "rev-parse", "HEAD").trim()
        val changes = git(checkout, "--no-optional-locks", "status", "--porcelain", "--untracked-files=no").trim()
        return if (changes.isEmpty()) head else "$head-dirty"
    }

    /**
     * The standard output of git run in [directory] with [arguments].
     *
     * @throws GradleException if git cannot be started, does not end in time or exits with a failure.
     */
    private fun git(directory: File, vararg arguments: String): String {
        val command = listOf("git", "-C", directory.absolutePath) + arguments
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (failure: java.io.IOException) {
            throw GradleException("cannot run git to read the karpfen-runtime sources: ${failure.message}", failure)
        }
        val error = StringBuilder()
        val errorReader = thread { error.append(process.errorStream.bufferedReader().readText()) }
        val output = process.inputStream.bufferedReader().readText()
        errorReader.join()
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException("git did not finish in time in $directory: ${arguments.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw GradleException("git ${arguments.joinToString(" ")} failed in $directory: ${error.toString().trim()}; run '$REMEDY'")
        }
        return output
    }

    /** The SHA-256 of [bytes], lower-case hex. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
