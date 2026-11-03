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
/**
 * Convention for modules with kotlinx.serialization, such as the environment descriptors of core-document and the
 * settings of the workbench: the Kotlin/JVM base convention plus the serialization compiler plugin and the JSON runtime.
 * The runtime protocol reads and writes the runtime's JSON with its own reader instead. It has to be a convention
 * plugin because the serialization plugin reaches the modules through build-logic's classpath and therefore cannot be
 * requested by id alone in a module's `plugins` block.
 */
plugins {
    id("karpfen.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
}
