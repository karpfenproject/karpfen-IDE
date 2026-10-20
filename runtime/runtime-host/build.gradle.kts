plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    implementation(project(":runtime:runtime-protocol"))
    // The only module allowed to use the Docker client; kept as implementation so Docker types never leak.
    implementation(libs.bundles.docker.java)
}
