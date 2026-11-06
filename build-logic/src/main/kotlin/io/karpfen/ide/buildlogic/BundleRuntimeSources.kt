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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Packs the karpfen-runtime sources of [checkout] into [outputDirectory] below [resourceDirectory], as
 * [RuntimeSourcesArchive] describes, for the IDE to build its runtime image from. Its inputs are the files git tracks
 * in the checkout, which [TrackedRuntimeFiles] lists, so it is up to date as long as they do not change. It fails with
 * the command that checks the sources out if the checkout or its nested submodule is missing.
 */
abstract class BundleRuntimeSources : DefaultTask() {
    /** The checkout of the karpfen-runtime submodule. */
    @get:Internal
    abstract val checkout: DirectoryProperty

    /** The files git tracks in [checkout]; their paths and contents decide whether the task is up to date. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val trackedFiles: ConfigurableFileCollection

    /** The resource folder below [outputDirectory] that receives the archive and its properties, such as `a/b/bundled`. */
    @get:Input
    abstract val resourceDirectory: Property<String>

    /** The root of the generated resources. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Writes the archive and its properties, replacing what an earlier run wrote. */
    @TaskAction
    fun bundle() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val summary = RuntimeSourcesArchive.pack(checkout.get().asFile, root.resolve(resourceDirectory.get()))
        logger.info("Bundled ${summary.fileCount} runtime source files of ${summary.runtimeCommit} as ${summary.contentHash}")
    }
}

/**
 * The paths of the files git tracks in a karpfen-runtime checkout, as [RuntimeSourcesArchive.trackedPathsOrEmpty] lists
 * them: the inputs of [BundleRuntimeSources]. Read through a value source, so the configuration cache runs git again on
 * every build and notices a file that was added or removed.
 */
abstract class TrackedRuntimeFiles : ValueSource<List<String>, TrackedRuntimeFiles.Parameters> {
    /** The parameters of the value source. */
    interface Parameters : ValueSourceParameters {
        /** The checkout of the karpfen-runtime submodule. */
        val checkout: DirectoryProperty
    }

    override fun obtain(): List<String> = RuntimeSourcesArchive.trackedPathsOrEmpty(parameters.checkout.get().asFile)
}
