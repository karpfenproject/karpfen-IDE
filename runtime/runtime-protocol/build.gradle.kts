plugins {
    id("karpfen.kotlin-serialization")
}

/**
 * The typed HTTP and WebSocket client for the karpfen runtime is built on the Ktor client; its data transfer objects use
 * kotlinx.serialization, which the convention plugin applies. Tests use Ktor's mock engine.
 */
dependencies {
    implementation(project(":core:core-text"))
    implementation(libs.bundles.ktor.client)

    testImplementation(libs.ktor.client.mock)
}
