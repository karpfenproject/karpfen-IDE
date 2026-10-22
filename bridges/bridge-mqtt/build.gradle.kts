plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * An MQTT bridge is a bridge of bridge-api, re-exported by bridge-mapping, configured with mappings. It is the only
 * module that uses the HiveMQ MQTT client.
 */
dependencies {
    api(project(":bridges:bridge-mapping"))
    implementation(libs.hivemq.mqtt.client)
}
