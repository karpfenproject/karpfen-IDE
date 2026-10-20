plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // Diagram elements are keyed by core-workspace element identities.
    api(project(":core:core-workspace"))
    // Auto-layout adapter; ELK stays an implementation detail behind it.
    implementation(libs.bundles.elk)
}
