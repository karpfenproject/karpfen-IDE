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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.WireNames

/** What [SnapshotReader.read] made of a body. */
internal sealed interface SnapshotReading {
    /**
     * The body is a snapshot.
     *
     * @property snapshot the snapshot.
     */
    data class Read(val snapshot: RuntimeModelSnapshot) : SnapshotReading

    /**
     * The body is not a snapshot as the runtime writes it.
     *
     * @property violation an [ViolationKind.UNEXPECTED_BODY] that says why, with the start of the body.
     */
    data class Unexpected(val violation: ProtocolViolation) : SnapshotReading
}

/**
 * Reads the body of the model snapshot route by member name: `environmentKey` a string that is a valid
 * [EnvironmentKey], `phase` the wire name of a [RuntimePhase], `revision` an integral number of at least 0,
 * `activeStates` an object whose members are arrays of strings (their order kept), and `roots` an array of objects that
 * each have a string `__id__` and a string `__type__`. Anything else is an [ViolationKind.UNEXPECTED_BODY]. Stateless.
 */
internal object SnapshotReader {

    /** Reads [body] as a snapshot. */
    fun read(body: String): SnapshotReading {
        val members = when (val reading = RuntimeJson.read(body)) {
            is JsonReading.Malformed -> return unexpected(body, "the body is not JSON: ${reading.reason} at offset ${reading.offset}")
            is JsonReading.Read -> reading.value as? WireValue.WireObject ?: return unexpected(body, "the body is not a JSON object")
        }
        val key = members.stringMember(WireNames.ENVIRONMENT_KEY)?.takeIf { it.isValidEnvironmentKey() }
            ?: return unexpected(body, "the member '${WireNames.ENVIRONMENT_KEY}' is missing or not an environment key")
        val phase = members.stringMember(WireNames.PHASE)?.let { RuntimePhase.ofWireName(it) }
            ?: return unexpected(body, "the member '${WireNames.PHASE}' is missing or not a known phase")
        val revision = members.integralMember(WireNames.REVISION)?.takeIf { it >= 0 }
            ?: return unexpected(body, "the member '${WireNames.REVISION}' is missing or not an integral number of at least 0")
        val activeStates = members.objectMember(WireNames.ACTIVE_STATES)?.let { activeStates(it) }
            ?: return unexpected(body, "the member '${WireNames.ACTIVE_STATES}' is missing or not an object of string arrays")
        val roots = (members[WireNames.ROOTS] as? WireValue.WireArray)?.let { roots(it) }
            ?: return unexpected(body, "the member '${WireNames.ROOTS}' is missing or not an array of the runtime's objects")
        return SnapshotReading.Read(RuntimeModelSnapshot(EnvironmentKey(key), phase, revision, activeStates, roots))
    }

    /** The stacks of [states] by machine in their order, or null if a member is not an array of strings. */
    private fun activeStates(states: WireValue.WireObject): Map<String, List<String>>? {
        val stacks = LinkedHashMap<String, List<String>>()
        states.fields.forEach { field ->
            val stack = (field.value as? WireValue.WireArray)?.strings() ?: return null
            stacks.putIfAbsent(field.name, stack)
        }
        return stacks
    }

    /** The root objects of [roots], or null if an item is not an object of the runtime. */
    private fun roots(roots: WireValue.WireArray): List<WireValue.WireObject>? =
        roots.items.map { item -> (item as? WireValue.WireObject)?.takeIf { it.isRuntimeObject() } ?: return null }

    /** True if this text satisfies the rule of an [EnvironmentKey]. */
    private fun String.isValidEnvironmentKey(): Boolean = runCatching { EnvironmentKey(this) }.isSuccess

    /** An unexpected body with [detail]. */
    private fun unexpected(body: String, detail: String): SnapshotReading =
        SnapshotReading.Unexpected(ProtocolViolation(ViolationKind.UNEXPECTED_BODY, detail, body.excerpt()))
}
