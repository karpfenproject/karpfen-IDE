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
import io.karpfen.ide.buildlogic.BundleRuntimeSources
import io.karpfen.ide.buildlogic.GitSubmodules
import io.karpfen.ide.buildlogic.LiveDockerTesting
import io.karpfen.ide.buildlogic.TestTags
import io.karpfen.ide.buildlogic.TrackedRuntimeFiles
import java.time.Duration

/**
 * The runtime of a workspace. It is either a container that the IDE builds from the runtime sources bundled with it and
 * runs in the local Docker engine, or a runtime started elsewhere that the IDE attaches to by URL. The IDE never runs the
 * runtime as a local Java process. The ordinary tests talk to Docker through a fake engine. The tests against the local
 * Docker engine run only in the opt-in task [LiveDockerTesting.TASK_NAME].
 */
plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * The only module allowed to use the Docker client. It is an implementation dependency, so Docker types never leak
 * into the API. The tests answer health checks with the fake runtime of runtime-protocol's test fixtures on virtual time.
 */
dependencies {
    api(project(":runtime:runtime-protocol"))
    implementation(libs.bundles.docker.java)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":runtime:runtime-protocol")))
}

/**
 * The checkout of the karpfen-runtime submodule. Its path is the `.gitmodules` entry whose last segment is
 * `karpfen-runtime`, as build-logic's `GitSubmodules` reads it. If the repository records no such submodule, it is the
 * directory `karpfen-runtime` at the repository root.
 */
val runtimeCheckout: Directory = layout.settingsDirectory.dir(
    GitSubmodules.parse(providers.fileContents(layout.settingsDirectory.file(".gitmodules")).asText.orNull.orEmpty())
        .firstOrNull { it.substringAfterLast('/') == "karpfen-runtime" } ?: "karpfen-runtime",
)

/**
 * Packs the files git tracks in the runtime checkout, those of its nested karpfen-dsl-tools submodule included, into the
 * resources of this module. The task writes the archive `runtime-sources.tar.gz` and the file
 * `runtime-sources.properties` below the package of the bundled sources, where `BundledRuntimeSources` reads them. The
 * properties record the archive's SHA-256, the runtime commit and the number of files. The archive is deterministic, so
 * the same sources always give the same hash. The Docker host builds the runtime image from this archive and tags the
 * image with the start of that hash. The resources of the main source set include the task's output, so
 * `processResources` runs it. Without the runtime submodule checked out, the task fails and names the git command that
 * checks it out.
 */
val bundleRuntimeSources = tasks.register<BundleRuntimeSources>("bundleRuntimeSources") {
    description = "Packs the tracked files of the karpfen-runtime checkout as the Docker build context of the runtime image."
    val root = runtimeCheckout
    checkout = root
    trackedFiles.from(
        providers.of(TrackedRuntimeFiles::class) { parameters.checkout = root }.map { paths -> paths.map { root.file(it) } },
    )
    resourceDirectory = "io/karpfen/ide/runtime/host/bundled"
    outputDirectory = layout.buildDirectory.dir("generated/runtime-sources")
}

sourceSets.main {
    resources.srcDir(bundleRuntimeSources)
}

/** The test sources, whose compiled classes the live task runs like the `test` task does. */
val testSourceSet = sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME)

/**
 * Runs the tests tagged [TestTags.LIVE_DOCKER] against the local Docker engine, without the time-budget tests. Start it
 * with `./gradlew :runtime:runtime-host:liveDockerTest`. It needs a local Docker engine. The engine test builds a tiny
 * image `FROM scratch`, labelled `io.karpfen.ide.test=true`, and removes it again. The host test builds the runtime
 * image from the bundled sources unless it exists, which takes minutes the first time. It then starts a container for a
 * workspace in a temporary folder, adopts it from a second host, stops it and removes every container it made. The
 * runtime image stays, because it is the IDE's own image.
 *
 * The task never counts as up to date and is never taken from the build cache, because its result depends on the
 * engine and not on its inputs. It fails when it finds no test, so a lost tag cannot pass silently. The timeout is a
 * hard upper bound for a hanging run, not an assertion on time. No other task depends on this one, so `test`, `check`
 * and `build` never run it.
 */
tasks.register<Test>(LiveDockerTesting.TASK_NAME) {
    group = LiveDockerTesting.TASK_GROUP
    description = "Runs the tests tagged live-docker against the local Docker engine; never part of check."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        excludeTags(TestTags.BUDGET)
        includeTags(TestTags.LIVE_DOCKER)
    }
    failOnNoDiscoveredTests = true
    doNotTrackState("Its result depends on the local Docker engine, not on its inputs")
    timeout = Duration.ofMinutes(30)
    shouldRunAfter(tasks.named("test"))
}
