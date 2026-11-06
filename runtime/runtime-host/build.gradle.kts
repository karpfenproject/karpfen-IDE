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
 * The runtime of a workspace: the container the IDE builds from the runtime sources bundled with it and runs in the local
 * Docker engine, or a runtime attached by URL. Its tests talk to Docker through a fake engine; the tests against the
 * local Docker engine run only in the opt-in task [LiveDockerTesting.TASK_NAME].
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
 * The checkout of the karpfen-runtime submodule: the `.gitmodules` entry whose last segment is `karpfen-runtime`, read
 * with build-logic's `GitSubmodules`, or the directory of that name if the repository records no such submodule.
 */
val runtimeCheckout: Directory = layout.settingsDirectory.dir(
    GitSubmodules.parse(providers.fileContents(layout.settingsDirectory.file(".gitmodules")).asText.orNull.orEmpty())
        .firstOrNull { it.substringAfterLast('/') == "karpfen-runtime" } ?: "karpfen-runtime",
)

/**
 * Packs the files git tracks in the runtime checkout into the resources of the module, below the package of the
 * bundled sources, where `BundledRuntimeSources` reads them; the IDE builds its runtime image from them. The resources
 * include the task's output, so `processResources` runs it.
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
 * Runs the tests tagged [TestTags.LIVE_DOCKER], without the time-budget tests, against the local Docker engine: they
 * build the runtime image from the bundled sources unless it exists, start and adopt a container and remove it again.
 * The task never counts as up to date and is never taken from the build cache, because its result depends on the
 * engine; it fails when it finds no test, so a lost tag cannot pass silently; and the timeout is a hard upper bound
 * for a hanging run, not an assertion on time. Nothing depends on it.
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
