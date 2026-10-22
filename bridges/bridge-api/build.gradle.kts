plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * The runtime protocol is part of the API: mappings translate between external messages and the karpfen events and
 * object changes of the protocol.
 */
dependencies {
    api(project(":runtime:runtime-protocol"))
}
