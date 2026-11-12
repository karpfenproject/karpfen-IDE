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
package io.karpfen.ide.ui.workbench.bridges

import io.karpfen.ide.bridges.api.Bridge
import io.karpfen.ide.bridges.api.BridgeConfig
import io.karpfen.ide.bridges.api.BridgeKind
import io.karpfen.ide.bridges.api.ModelLink
import io.karpfen.ide.bridges.http.HttpBridge
import io.karpfen.ide.bridges.http.HttpSettings
import io.karpfen.ide.bridges.mapping.BridgeConfigFile
import io.karpfen.ide.bridges.mqtt.MqttBridge
import io.karpfen.ide.bridges.mqtt.MqttSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A bridge file as a run takes it up.
 *
 * @property file the bridge file.
 * @property name the file relative to the project folder with forward slashes, which names the bridge to the user.
 * @property config what the file configures, or null if the bridge cannot be started.
 * @property problem why the bridge cannot be started, or null if it can.
 */
internal data class BridgePlan(val file: Path, val name: String, val config: BridgeConfig?, val problem: String?)

/**
 * Reads the bridge files a run starts and builds their bridges.
 *
 * A file that cannot be read, that is not a bridge file, or whose settings make no sense gives a plan with a problem
 * and no configuration. The run then shows the bridge as failed and starts the others. An MQTT bridge whose file names
 * no client id gets one from the name of its file, because a broker lets only one client with the same id stay
 * connected, and two MQTT bridges of one run must not push each other out.
 *
 * @param ioDispatcher where the HTTP bridge starts and stops its server, which blocks for a moment.
 */
internal class BridgeLoader(private val ioDispatcher: CoroutineDispatcher) {

    /** The plan for the bridge [file] of the project at [root]. Reads the file, so call it where blocking is fine. */
    fun plan(root: Path, file: Path): BridgePlan {
        val name = nameOf(root, file)
        val text = try {
            Files.readString(file)
        } catch (failure: IOException) {
            return BridgePlan(file, name, null, "The bridge file could not be read: ${failure.message ?: failure.javaClass.simpleName}")
        }
        val reading = BridgeConfigFile.read(text)
        val config = reading.config
        val problems = reading.problems + if (config == null) emptyList() else settingsProblems(config)
        return when {
            problems.isNotEmpty() -> BridgePlan(file, name, null, problems.joinToString(" "))
            config == null -> BridgePlan(file, name, null, "The file is not a bridge file.")
            config.kind == BridgeKind.ROS -> BridgePlan(file, name, null, "The IDE cannot run ROS bridges yet.")
            else -> BridgePlan(file, name, withDefaults(config, file), null)
        }
    }

    /** The bridge for [config] between the model behind [link] and its external system, working in [scope]. */
    fun bridge(config: BridgeConfig, link: ModelLink, scope: CoroutineScope): Bridge = when (config.kind) {
        BridgeKind.HTTP -> HttpBridge(config, link, scope, ioDispatcher)
        BridgeKind.MQTT -> MqttBridge(config, link, scope)
        BridgeKind.ROS -> error("there is no ROS bridge")
    }

    private fun settingsProblems(config: BridgeConfig): List<String> = when (config.kind) {
        BridgeKind.HTTP -> HttpSettings.problemsOf(config.settings)
        BridgeKind.MQTT -> MqttSettings.problemsOf(config.settings)
        BridgeKind.ROS -> emptyList()
    }

    private fun withDefaults(config: BridgeConfig, file: Path): BridgeConfig =
        if (config.kind == BridgeKind.MQTT && config.settings[MqttSettings.CLIENT_ID].isNullOrBlank()) {
            val id = file.fileName.toString().removeSuffix(BridgeConfigFile.SUFFIX)
            config.copy(settings = config.settings + (MqttSettings.CLIENT_ID to "karpfen-$id"))
        } else {
            config
        }

    companion object {
        /** [file] relative to [root] with forward slashes, or its whole path if it is not below [root]. */
        fun nameOf(root: Path, file: Path): String =
            if (file.startsWith(root) && file != root) root.relativize(file).joinToString("/") { it.toString() } else file.toString()
    }
}
