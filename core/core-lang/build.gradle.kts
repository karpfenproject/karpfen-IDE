plugins {
    id("karpfen.kotlin-jvm")
}

/** The language services take document types as parameters and read the workspace's project analysis. */
dependencies {
    api(project(":core:core-workspace"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
}
