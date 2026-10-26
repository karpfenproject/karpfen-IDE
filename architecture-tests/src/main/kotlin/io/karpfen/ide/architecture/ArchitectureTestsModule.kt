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
package io.karpfen.ide.architecture

/**
 * Production code of the `architecture-tests` module, whose Konsist rules live in the test source set. The rules keep
 * the layers, the adapters of foreign APIs and the package layout of every module in shape; this object gives the
 * module the production file that `PackageLayoutTest` expects of every module in the module table.
 */
public object ArchitectureTestsModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "architecture-tests"
}
