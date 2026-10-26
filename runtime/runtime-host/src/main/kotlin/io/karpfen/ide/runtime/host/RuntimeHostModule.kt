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

/**
 * Placeholder of the `runtime-host` module, kept from the phase-0 skeleton until a later increment builds it. The
 * module is to hold the hosts of the runtime: an IDE-owned Docker container by default, attaching to a running runtime,
 * and a local process. The README lists it among the modules of later increments.
 */
public object RuntimeHostModule {
    /** The module's name, which is also the name of its directory. */
    public const val ID: String = "runtime-host"
}
