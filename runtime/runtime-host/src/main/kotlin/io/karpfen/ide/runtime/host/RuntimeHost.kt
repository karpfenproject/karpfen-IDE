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
package io.karpfen.ide.runtime.host

import io.karpfen.ide.runtime.protocol.catalogue.RuntimeEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * The ways the IDE reaches a karpfen runtime. There are exactly two: a container the IDE owns, and a runtime started
 * elsewhere that the IDE attaches to by URL. The IDE never runs the runtime as a local Java process.
 */
public enum class RuntimeHostKind {
    /** A Docker container that the IDE builds, starts, watches and stops, one per workspace. */
    DOCKER,

    /** A runtime started elsewhere, reached at a URL the user gives. */
    URL,
}

/**
 * What the user chose for the runtime of a workspace.
 *
 * @property kind how the runtime is reached.
 * @property url the base URL of the runtime, used when [kind] is [RuntimeHostKind.URL], for example
 *   `http://127.0.0.1:8080`.
 */
public data class RuntimeHostSettings(
    public val kind: RuntimeHostKind = RuntimeHostKind.DOCKER,
    public val url: String = DEFAULT_URL,
) {
    public companion object {
        /** The URL a runtime started with its own defaults listens on. */
        public const val DEFAULT_URL: String = "http://127.0.0.1:8080"
    }
}

/** The steps a host goes through before its runtime answers. */
public enum class HostStage {
    /** Finding the local Docker engine and checking that it answers. */
    CHECKING_ENGINE,

    /** Building the runtime image from the runtime sources bundled with the IDE. */
    BUILDING_IMAGE,

    /** Creating the container and handing it its configuration. */
    CREATING_CONTAINER,

    /** Starting the container. */
    STARTING,

    /** Waiting until the runtime answers its health check and supports the IDE. */
    WAITING_FOR_HEALTH,
}

/** Why a host has no runtime to offer. */
public enum class HostProblemCode {
    /** No local Docker engine answers. */
    ENGINE_UNAVAILABLE,

    /** The configured Docker endpoint is not local, so a container would publish the runtime elsewhere. */
    ENGINE_NOT_LOCAL,

    /** The runtime sources bundled with the IDE are missing or unreadable. */
    SOURCES_MISSING,

    /** Building the runtime image failed. */
    IMAGE_BUILD_FAILED,

    /** Creating or starting the container failed. */
    CONTAINER_FAILED,

    /** The container ran but the runtime never became healthy in time. */
    UNHEALTHY,

    /** The container stopped while the IDE still used it. */
    CONTAINER_DIED,

    /** The URL is malformed. */
    URL_INVALID,

    /** Nothing answers at the URL. */
    URL_UNREACHABLE,

    /** A runtime answers at the URL, but without the support the IDE needs. */
    NO_IDE_SUPPORT,
}

/**
 * A reason, in words for the user, why a host has no runtime.
 *
 * @property code the kind of problem.
 * @property message what went wrong.
 * @property hint what the user can do about it, if anything.
 */
public data class HostProblem(public val code: HostProblemCode, public val message: String, public val hint: String? = null)

/** What a host is doing, as the UI shows it. */
public sealed interface HostStatus {
    /** No runtime is running and none is being started. */
    public data object Stopped : HostStatus

    /**
     * A runtime is being prepared.
     *
     * @property stage the current step.
     * @property detail a short line about the step's progress, for example the image build step.
     */
    public data class Preparing(public val stage: HostStage, public val detail: String = "") : HostStatus

    /**
     * The runtime answers and supports the IDE.
     *
     * @property endpoint where the runtime is reached.
     * @property description a short line for the status bar, for example the image and the published port.
     */
    public data class Running(public val endpoint: RuntimeEndpoint, public val description: String) : HostStatus

    /**
     * The host has no runtime because of [problem]. A later [RuntimeHost.ensureRunning] tries again.
     *
     * @property problem why.
     */
    public data class Failed(public val problem: HostProblem) : HostStatus

    /** The runtime is being stopped. */
    public data object Stopping : HostStatus
}

/** The answer of [RuntimeHost.ensureRunning]. */
public sealed interface HostOutcome {
    /**
     * The runtime runs and answers at [endpoint].
     *
     * @property endpoint where the runtime is reached.
     */
    public data class Ready(public val endpoint: RuntimeEndpoint) : HostOutcome

    /**
     * There is no runtime because of [problem].
     *
     * @property problem why.
     */
    public data class Failed(public val problem: HostProblem) : HostOutcome
}

/** The output stream a log line came from. */
public enum class LogStream {
    /** The runtime's standard output, or a line the host itself writes about its work. */
    STDOUT,

    /** The runtime's standard error. */
    STDERR,
}

/**
 * One line of the runtime's log, or of the host's own account of preparing it, such as the image build.
 *
 * @property text the line without its line break.
 * @property stream where it came from.
 */
public data class HostLogLine(public val text: String, public val stream: LogStream = LogStream.STDOUT)

/**
 * The runtime of one workspace and its lifecycle. The UI asks it for a runtime before each deployment, shows its
 * [status] and [logs], and stops it when the workspace closes or the IDE exits.
 *
 * Every member is safe to call from any thread, and the suspending members never block the caller's thread.
 * [ensureRunning] is idempotent while a runtime runs. A call while a start is under way joins that start instead of
 * starting a second one. [stop] and [close] never touch anything the host did not create.
 */
public interface RuntimeHost : AutoCloseable {
    /** How this host reaches its runtime. */
    public val kind: RuntimeHostKind

    /** What the host is doing now. */
    public val status: StateFlow<HostStatus>

    /**
     * The runtime's log and the host's account of preparing it, as a hot stream that replays the recent lines to a
     * new collector. A host that cannot read the runtime's log, such as one attached by URL, says so in one line.
     */
    public val logs: Flow<HostLogLine>

    /** Starts the runtime if it does not run yet, and answers where it is reached or why there is none. */
    public suspend fun ensureRunning(): HostOutcome

    /** Stops the runtime if this host owns it. A host attached by URL only forgets its runtime. */
    public suspend fun stop()

    /** Stops the runtime as [stop] does, within a short bound, and releases the host's resources. */
    override fun close()
}

/** Creates the host of a workspace from the user's choice. */
public fun interface RuntimeHostFactory {
    /**
     * The host for the workspace at [workspaceRoot] as [settings] choose it.
     *
     * @param workspaceRoot the root folder of the workspace, which names and labels the workspace's container.
     * @param settings how the runtime is reached.
     */
    public fun create(workspaceRoot: Path, settings: RuntimeHostSettings): RuntimeHost
}
