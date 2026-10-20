plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // An MQTT bridge is a Bridge (bridge-api, re-exported by bridge-mapping) configured with mappings.
    api(project(":bridges:bridge-mapping"))
    implementation(libs.hivemq.mqtt.client)
}
