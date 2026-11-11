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
import io.karpfen.ide.buildlogic.LiveRuntimeLock
import io.karpfen.ide.buildlogic.LiveRuntimeTarget
import io.karpfen.ide.buildlogic.LiveRuntimeTesting
import io.karpfen.ide.buildlogic.TestTags
import java.time.Duration

/**
 * Convention for the runtime modules whose tests also run against a real karpfen runtime. It adds the opt-in task
 * [LiveRuntimeTesting.TASK_NAME] to the Kotlin/JVM base convention. The task runs only the tests tagged
 * [TestTags.LIVE_RUNTIME] against the runtime at `-Pkarpfen.runtime.url`. The base convention excludes that tag from
 * every other test task, so the live tests compile in every build and run only here. Nothing makes `check`, `build`,
 * `test` or any other task depend on the live task. `runtime/live-runtime.sh test` starts a throwaway runtime in Docker
 * and runs the live tasks against it. Any runtime with IDE support that is reachable by URL works as well.
 */
plugins {
    id("karpfen.kotlin-jvm")
}

check(path.startsWith(":runtime:")) {
    "karpfen.live-runtime-test may only be applied to runtime modules, not $path"
}

/**
 * The one lock of the runtime under test, shared by the live tasks of every runtime module of the build, so that they
 * run one after the other.
 */
val liveRuntime = gradle.sharedServices.registerIfAbsent(LiveRuntimeTesting.LOCK_SERVICE, LiveRuntimeLock::class) {
    maxParallelUsages = 1
}

/** The test sources, whose compiled classes the live task runs like the `test` task does. */
val testSourceSet = sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME)

/**
 * Runs the tests tagged [TestTags.LIVE_RUNTIME], without the time-budget tests, against the runtime that
 * [LiveRuntimeTarget] hands the test JVM. Without a URL the task fails before any test runs. It never counts as up to
 * date and is never taken from the build cache, because its result depends on the runtime it talks to and not on its
 * inputs. It fails when it finds no test, so a lost tag cannot pass silently. The timeout is a hard upper bound for a
 * hanging run, not an assertion on time. The shared lock keeps it from running at the same time as the live task of
 * another runtime module.
 */
tasks.register<Test>(LiveRuntimeTesting.TASK_NAME) {
    group = LiveRuntimeTesting.TASK_GROUP
    description = "Runs the tests tagged live-runtime against the runtime at -Pkarpfen.runtime.url; never part of check."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        excludeTags(TestTags.BUDGET)
        includeTags(TestTags.LIVE_RUNTIME)
    }
    jvmArgumentProviders.add(
        LiveRuntimeTarget(
            providers.gradleProperty(LiveRuntimeTesting.URL_PROPERTY),
            providers.gradleProperty(LiveRuntimeTesting.LOGS_PROPERTY),
        ),
    )
    usesService(liveRuntime)
    failOnNoDiscoveredTests = true
    doNotTrackState("Its result depends on the runtime it talks to, not on its inputs")
    timeout = Duration.ofMinutes(30)
    shouldRunAfter(tasks.named("test"))
}
