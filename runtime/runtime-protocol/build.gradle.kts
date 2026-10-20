plugins {
    id("karpfen.kotlin-serialization")
}

dependencies {
    implementation(project(":core:core-text"))
    // Typed HTTP/WebSocket client for the runtime (proposal §5.6); DTOs use kotlinx.serialization.
    implementation(libs.bundles.ktor.client)

    testImplementation(libs.ktor.client.mock)
}
