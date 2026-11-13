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
package io.karpfen.ide.runtime.protocol.api

import io.karpfen.ide.runtime.protocol.catalogue.AccessKey
import io.karpfen.ide.runtime.protocol.catalogue.ClientId
import io.karpfen.ide.runtime.protocol.catalogue.EnvironmentKey
import io.karpfen.ide.runtime.protocol.catalogue.FeatureName
import io.karpfen.ide.runtime.protocol.catalogue.ResponseKind
import io.karpfen.ide.runtime.protocol.catalogue.WireNames
import io.karpfen.ide.runtime.protocol.message.JsonReading
import io.karpfen.ide.runtime.protocol.message.RuntimeJson
import io.karpfen.ide.runtime.protocol.message.RuntimeModelSnapshot
import io.karpfen.ide.runtime.protocol.message.SnapshotReader
import io.karpfen.ide.runtime.protocol.message.SnapshotReading
import io.karpfen.ide.runtime.protocol.message.WireValue
import org.slf4j.LoggerFactory

/** What a [SuccessBody] made of the body of a successful answer. */
internal sealed interface BodyReading<out T> {
    /**
     * The body is what the route answers.
     *
     * @property value the body, read.
     */
    data class Read<out T>(val value: T) : BodyReading<T>

    /**
     * The body is not what the route answers.
     *
     * @property detail why, in words.
     */
    data class Unexpected(val detail: String) : BodyReading<Nothing>
}

/** Reads the body of a successful answer (200) of a route, by the route's [ResponseKind]. */
internal fun interface SuccessBody<out T> {
    /** Reads [body]. */
    fun read(body: String): BodyReading<T>
}

/**
 * The readers of the success bodies, one per [ResponseKind].
 *
 * - [EMPTY] accepts any body. The runtime answers with an empty text. A body that is not empty is only logged at debug
 *   level.
 * - [ENVIRONMENT_KEY] expects `env-` followed by digits.
 * - [accessKey] expects `ak-<the requested client id>-<digits>-<one to four digits>`.
 * - [HEALTH] expects a JSON object whose member `status` is the string `ok`.
 * - [ENVIRONMENT_LIST] expects a JSON array of objects. Each object has a string `envKey` and an array of strings
 *   `modelElements`. The members may come in any order, and other members are ignored.
 * - [STATE_MACHINE_SOURCE] takes the body as it is.
 * - [snapshot] reads the snapshot as [SnapshotReader] does. The snapshot's environment must be the requested one.
 * - [FEATURE_NAMES] reads feature names separated by [WireNames.FEATURE_SEPARATOR]. An empty body names none. Every
 *   name must be a [FeatureName].
 * - [FEATURE_ANSWER] takes the body as it is.
 */
internal object SuccessBodies {

    /** The logger of unexpected but harmless bodies. */
    private val logger = LoggerFactory.getLogger(SuccessBodies::class.java)

    /** The member of a listed environment that holds its key. */
    private const val LISTED_KEY = "envKey"

    /** The member of a listed environment that holds the `attachedTo` keys of its machines. */
    private const val LISTED_ELEMENTS = "modelElements"

    /** The member of the health answer that holds the status. */
    private const val HEALTH_STATUS = "status"

    /** The status of a healthy runtime. */
    private const val HEALTHY = "ok"

    /** An environment key as the runtime issues it. */
    private val ENVIRONMENT_KEY_PATTERN = Regex(Regex.escape(WireNames.ENVIRONMENT_KEY_PREFIX) + """\d+""")

    /** Any body. Nothing is read. */
    val EMPTY: SuccessBody<Unit> = SuccessBody { body ->
        if (body.isNotEmpty()) logger.debug("The runtime answered a call without a result with the body '{}'", body.take(LOGGED_LENGTH))
        BodyReading.Read(Unit)
    }

    /** A new environment key. */
    val ENVIRONMENT_KEY: SuccessBody<EnvironmentKey> = SuccessBody { body ->
        if (ENVIRONMENT_KEY_PATTERN.matches(body)) {
            BodyReading.Read(EnvironmentKey(body))
        } else {
            BodyReading.Unexpected("the body is not an environment key")
        }
    }

    /** The health answer. */
    val HEALTH: SuccessBody<Unit> = SuccessBody { body ->
        val answer = (RuntimeJson.read(body) as? JsonReading.Read)?.value as? WireValue.WireObject
        if ((answer?.get(HEALTH_STATUS) as? WireValue.WireString)?.value == HEALTHY) {
            BodyReading.Read(Unit)
        } else {
            BodyReading.Unexpected("the body is not a JSON object with the status '$HEALTHY'")
        }
    }

    /** The list of the environments the runtime lists. */
    val ENVIRONMENT_LIST: SuccessBody<List<ActiveEnvironment>> = SuccessBody { body ->
        val items = ((RuntimeJson.read(body) as? JsonReading.Read)?.value as? WireValue.WireArray)?.items
        val environments = items?.map { item -> listedEnvironment(item) ?: return@SuccessBody unexpectedList() }
        if (environments != null) BodyReading.Read(environments) else unexpectedList()
    }

    /** The uploaded text of a state machine, as it is. */
    val STATE_MACHINE_SOURCE: SuccessBody<String> = SuccessBody { body -> BodyReading.Read(body) }

    /** The names of features, as a set. */
    val FEATURE_NAMES: SuccessBody<Set<FeatureName>> = SuccessBody { body ->
        if (body.isEmpty()) return@SuccessBody BodyReading.Read(emptySet())
        val names = body.split(WireNames.FEATURE_SEPARATOR).map { name ->
            runCatching { FeatureName(name) }.getOrNull()
                ?: return@SuccessBody BodyReading.Unexpected("the body is not a list of feature names separated by '${WireNames.FEATURE_SEPARATOR}'")
        }
        BodyReading.Read(names.toSet())
    }

    /** The answer of a feature to a message, as it is. */
    val FEATURE_ANSWER: SuccessBody<String> = SuccessBody { body -> BodyReading.Read(body) }

    /** A new access key of [client]. */
    fun accessKey(client: ClientId): SuccessBody<AccessKey> {
        val pattern = Regex(Regex.escape(WireNames.ACCESS_KEY_PREFIX + client.value + "-") + """\d+-\d{1,4}""")
        return SuccessBody { body ->
            if (pattern.matches(body)) {
                BodyReading.Read(AccessKey(body))
            } else {
                BodyReading.Unexpected("the body is not an access key of client '${client.value}'")
            }
        }
    }

    /** A snapshot of [environment]. */
    fun snapshot(environment: EnvironmentKey): SuccessBody<RuntimeModelSnapshot> = SuccessBody { body ->
        when (val reading = SnapshotReader.read(body)) {
            is SnapshotReading.Unexpected -> BodyReading.Unexpected(reading.violation.detail)
            is SnapshotReading.Read -> if (reading.snapshot.environment == environment) {
                BodyReading.Read(reading.snapshot)
            } else {
                BodyReading.Unexpected("the snapshot is of environment '${reading.snapshot.environment}', not of '$environment'")
            }
        }
    }

    /** The environment [item] of the list, or null if it is not one. */
    private fun listedEnvironment(item: WireValue): ActiveEnvironment? {
        val members = item as? WireValue.WireObject ?: return null
        val key = (members[LISTED_KEY] as? WireValue.WireString)?.value ?: return null
        val elements = (members[LISTED_ELEMENTS] as? WireValue.WireArray)?.items
            ?.map { (it as? WireValue.WireString)?.value ?: return null } ?: return null
        val environment = runCatching { EnvironmentKey(key) }.getOrNull() ?: return null
        return ActiveEnvironment(environment, elements)
    }

    /** The reading of a body that is not a list of environments. */
    private fun unexpectedList(): BodyReading<Nothing> =
        BodyReading.Unexpected("the body is not a JSON array of environments with '$LISTED_KEY' and '$LISTED_ELEMENTS'")

    /** The longest part of an unexpected body that is logged. */
    private const val LOGGED_LENGTH = 200
}
