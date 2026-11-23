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
 * The diagram model without any UI, derived from the outline of a document, and its layout, automatic or by hand. The
 * layout library ELK stays an implementation detail behind the layouter. The JSON library reads and writes the
 * companion file that holds a layout made by hand. The language services of core-lang write the whole objects of a
 * type that the object diagram adds, the same text their completion inserts, and the actions of the object diagram take
 * that writer in their API.
 */
dependencies {
    api(project(":core:core-workspace"))
    api(project(":core:core-lang"))
    implementation(libs.bundles.elk)
    implementation(libs.kotlinx.serialization.json)
}

/**
 * The test that reads the example texts of the reference chapter on diagrams finds the reference through the system
 * property `karpfen.reference`, and the folder is an input of the tests.
 */
tasks.withType<Test>().configureEach {
    val reference = rootProject.layout.projectDirectory.dir("docs/reference")
    inputs.dir(reference).withPropertyName("reference").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("karpfen.reference", reference.asFile.absolutePath)
}
