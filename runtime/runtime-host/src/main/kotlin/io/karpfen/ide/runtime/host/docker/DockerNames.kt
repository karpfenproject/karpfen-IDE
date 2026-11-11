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
 * The labels of the Docker resources the IDE creates for its runtime. Every image and every container of the IDE's
 * runtime carries [RUNTIME] with the value `true`. An image also carries the hash of the sources it was built from and
 * the runtime commit. A container also carries its workspace path, the short hash of that path and the hash of the
 * sources it runs. The host only ever lists, stops or removes containers and images that carry [RUNTIME], and
 * containers only of its own workspace, which it finds by [WORKSPACE_HASH].
 *
 * To remove by hand everything the host created, remove the containers first and then the images:
 * `docker rm --force --volumes $(docker ps --all --quiet --filter label=io.karpfen.ide.runtime=true)` and then
 * `docker rmi $(docker images --quiet --filter label=io.karpfen.ide.runtime=true)`.
 *
 * A runtime build also leaves images without the label, and the host never touches them. The runtime's `Dockerfile` has
 * a build stage that compiles the runtime and a runtime stage. The engine labels only the image of the last stage, so
 * every build normally leaves the build stage behind as an untagged image. The build also pulls the base images
 * `eclipse-temurin:21-jdk` and `debian:trixie-slim`. `docker images --filter dangling=true` lists the untagged images,
 * those of other builds as well. The ones created at the time of a runtime build can go with `docker rmi <id>`. The base
 * images can go with `docker rmi eclipse-temurin:21-jdk debian:trixie-slim` when nothing else builds on them.
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
 * The runtime image built from the bundled sources whose SHA-256 is [contentHash]. Its tag is
 * `karpfen-ide/runtime:<first 12 hex digits of the hash>`. New sources therefore get a new image, and the same sources
 * always reuse the image built before. The first build takes minutes. Later starts find the tag and skip the build.
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
 * The container of the workspace at [workspaceRoot], with its name and labels derived from the absolute, normalized
 * path. The name is `karpfen-ide-runtime-<first 10 hex digits of the SHA-256 of the path>`. Each workspace thus has one
 * container name, and a second host of the same workspace finds and adopts the same container. The container publishes
 * the runtime's port 8080 on `127.0.0.1` at an ephemeral host port, so only this machine reaches it.
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
