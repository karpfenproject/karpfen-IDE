plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * The workspace hands out documents, sessions, diagnostics and the dsl-tools models of the project analysis, all from
 * core-document, which also brings kotlinx.serialization, the reader of environment descriptors.
 */
dependencies {
    api(project(":core:core-document"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
