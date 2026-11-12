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
plugins {
    id("karpfen.application")
}

/**
 * The start script is named `karpfen-bridge-runner`, matching the usage line that the runner prints. `installDist` puts
 * it at `build/install/karpfen-bridge-runner/bin/karpfen-bridge-runner`.
 */
application {
    mainClass = "io.karpfen.ide.bridges.runner.MainKt"
    applicationName = "karpfen-bridge-runner"
}

/**
 * The headless runner of the bridges, which so far only prints its usage. It will start the HTTP, MQTT and ROS
 * bridges, and it logs through Logback at run time.
 */
dependencies {
    implementation(project(":bridges:bridge-http"))
    implementation(project(":bridges:bridge-mqtt"))
    implementation(project(":bridges:bridge-ros"))
    runtimeOnly(libs.logback.classic)
}
