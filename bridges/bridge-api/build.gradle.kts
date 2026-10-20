plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // Mappings translate between external messages and karpfen events/object changes from the protocol.
    api(project(":runtime:runtime-protocol"))
}
