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
package io.karpfen.ide.runtime.protocol.conformance

import org.junit.jupiter.api.Tag

/**
 * Marks a test class that runs its scenarios against a real karpfen runtime, the live subclass of a scenario class,
 * named `Live…Test`. It carries the JUnit tag `live-runtime` (build-logic's `TestTags.LIVE_RUNTIME`), which every test
 * task excludes except the `liveRuntimeTest` task of the runtime modules, so a live test compiles in every build and
 * runs only against a runtime someone hands the task. The architecture test `LiveRuntimeTaggingTest` checks that only
 * runtime modules use it and that every class named `Live…Test` carries it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("live-runtime")
public annotation class LiveRuntime
