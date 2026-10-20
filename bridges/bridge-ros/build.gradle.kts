plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // A rosbridge bridge is a Bridge (bridge-api, re-exported by bridge-mapping) configured with mappings.
    api(project(":bridges:bridge-mapping"))
    // rosbridge protocol: JSON over WebSocket.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
}
