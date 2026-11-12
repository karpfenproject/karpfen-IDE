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
    id("karpfen.kotlin-jvm")
}

/**
 * The ROS bridge. It is a bridge of bridge-api, which bridge-mapping re-exports, and it runs the routes of a bridge
 * file over the topics of ROS 2, or of ROS 1 when the bridge file says so. It speaks the rosbridge protocol, which is
 * JSON over WebSocket, through the Ktor client, so it needs no native ROS library. Its tests run against a small
 * WebSocket server inside the test that stands in for a rosbridge server.
 */
dependencies {
    api(project(":bridges:bridge-mapping"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}
