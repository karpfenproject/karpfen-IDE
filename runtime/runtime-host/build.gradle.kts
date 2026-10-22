plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * The only module allowed to use the Docker client. It is an implementation dependency, so Docker types never leak
 * into the API.
 */
dependencies {
    implementation(project(":runtime:runtime-protocol"))
    implementation(libs.bundles.docker.java)
}
