plugins {
    /** Environment descriptors (`*.kenv.json`) are read with kotlinx.serialization. */
    id("karpfen.kotlin-serialization")
}

dependencies {
    /** The workspace hands out documents, sessions, diagnostics and the dsl-tools models of the project analysis. */
    api(project(":core:core-document"))

    /** `EnvironmentDescriptor` is `@Serializable` public API: its JSON shape is the contract with the runtime. */
    api(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
