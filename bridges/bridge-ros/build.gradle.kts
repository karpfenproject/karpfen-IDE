plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * A ROS bridge is a bridge of bridge-api, re-exported by bridge-mapping, configured with mappings. It speaks the
 * rosbridge protocol, JSON over WebSocket, through the Ktor client.
 */
dependencies {
    api(project(":bridges:bridge-mapping"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
}
