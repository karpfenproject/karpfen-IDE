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

import io.karpfen.ide.runtime.host.docker.engine.ContainerSpec
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The labels of the Docker resources the IDE creates for its runtime. The host only ever lists, stops or removes
 * containers and images that carry [RUNTIME], and containers only of its own workspace ([WORKSPACE_HASH]).
 */
internal object RuntimeLabels {
    /** Marks every image and container of the IDE's runtime, with the value `true`. */
    const val RUNTIME = "io.karpfen.ide.runtime"

    /** The SHA-256 of the bundled runtime sources an image was built from, or a container runs. */
    const val RUNTIME_HASH = "io.karpfen.ide.runtime.hash"

    /** The runtime commit an image was built from. */
    const val RUNTIME_COMMIT = "io.karpfen.ide.runtime.commit"

    /** The absolute path of the workspace a container belongs to. */
    const val WORKSPACE = "io.karpfen.ide.workspace"

    /** The short hash of that path, which names the container. */
    const val WORKSPACE_HASH = "io.karpfen.ide.workspace.hash"

    /** The value of [RUNTIME]. */
    const val TRUE = "true"

    /** The filter that finds every image and container of the IDE's runtime. */
    val OURS: Map<String, String> = mapOf(RUNTIME to TRUE)
}

/**
 * The runtime image built from the bundled sources whose SHA-256 is [contentHash].
 *
 * @property contentHash the SHA-256 of the bundled sources, lower-case hex.
 * @param runtimeCommit the runtime commit of the sources.
 */
internal class RuntimeImage(val contentHash: String, runtimeCommit: String) {
    /** The first twelve hex digits of [contentHash], which tag the image and describe it to the user. */
    val shortHash: String = contentHash.take(SHORT_HASH_LENGTH)

    /** The tag, `karpfen-ide/runtime:<shortHash>`. */
    val tag: String = "$REPOSITORY:$shortHash"

    /** The labels of the image. */
    val labels: Map<String, String> = mapOf(
        RuntimeLabels.RUNTIME to RuntimeLabels.TRUE,
        RuntimeLabels.RUNTIME_HASH to contentHash,
        RuntimeLabels.RUNTIME_COMMIT to runtimeCommit,
    )

    private companion object {
        /** The repository of the image tags. */
        const val REPOSITORY = "karpfen-ide/runtime"

        /** The number of hex digits of the content hash in the tag. */
        const val SHORT_HASH_LENGTH = 12
    }
}

/**
 * The container of the workspace at [workspaceRoot]: its name and labels, derived from the absolute, normalized path.
 *
 * @param workspaceRoot the root folder of the workspace.
 */
internal class WorkspaceContainer(workspaceRoot: Path) {
    /** The absolute, normalized path of the workspace. */
    val path: String = workspaceRoot.toAbsolutePath().normalize().toString()

    /** The first ten hex digits of the SHA-256 of [path]. */
    val hash: String = sha256(path).take(WORKSPACE_HASH_LENGTH)

    /** The container name, `karpfen-ide-runtime-<hash>`. */
    val name: String = "$NAME_PREFIX$hash"

    /** The filter that finds the containers of this workspace. */
    val filter: Map<String, String> = mapOf(RuntimeLabels.RUNTIME to RuntimeLabels.TRUE, RuntimeLabels.WORKSPACE_HASH to hash)

    /** The container of this workspace running [image]. */
    fun spec(image: RuntimeImage): ContainerSpec =
        ContainerSpec(
            image = image.tag,
            name = name,
            labels = mapOf(
                RuntimeLabels.RUNTIME to RuntimeLabels.TRUE,
                RuntimeLabels.WORKSPACE to path,
                RuntimeLabels.WORKSPACE_HASH to hash,
                RuntimeLabels.RUNTIME_HASH to image.contentHash,
            ),
        )

    private companion object {
        /** The prefix of the container names. */
        const val NAME_PREFIX = "karpfen-ide-runtime-"

        /** The number of hex digits of the path's hash in the name. */
        const val WORKSPACE_HASH_LENGTH = 10

        /** The SHA-256 of the UTF-8 bytes of [text], lower-case hex. */
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
