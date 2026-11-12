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
 * The bridge file and the engine that runs its routes. It works on the types of bridge-api, which is therefore part
 * of its API.
 */
dependencies {
    api(project(":bridges:bridge-api"))

    testImplementation(libs.kotlinx.coroutines.test)
}
