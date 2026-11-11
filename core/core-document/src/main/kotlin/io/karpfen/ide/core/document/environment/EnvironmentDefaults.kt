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
package io.karpfen.ide.core.document.environment

/**
 * What the absent timing keys of an [EnvironmentDescriptor] mean. The descriptor format owns the meaning of its absent
 * keys, so the deploy configuration of an environment has one source. That source is the descriptor, with these values
 * where it says nothing. The IDE's deployment always sends both values to the runtime, so the runtime's own configured
 * defaults never apply to an environment that the IDE deploys. The key documentation that hover and completion show is
 * built from these values.
 */
public object EnvironmentDefaults {
    /** The tick delay of an environment whose descriptor has no `tickDelayMs`, in milliseconds. */
    public const val TICK_DELAY_MS: Long = 50

    /** The event time to live of an environment whose descriptor has no `eventTtlMs`, in milliseconds. */
    public const val EVENT_TTL_MS: Long = 3_000
}
