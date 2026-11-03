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

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The lock of the one runtime that the live test tasks share. It holds no state: the `karpfen.live-runtime-test`
 * convention plugin registers it as [LiveRuntimeTesting.LOCK_SERVICE] with one parallel usage, and every live task uses
 * it, so the live tasks of runtime-protocol and runtime-session run one after the other although the repository builds
 * with parallel projects, and their scenarios never load the runtime at the same time.
 */
abstract class LiveRuntimeLock : BuildService<BuildServiceParameters.None>
