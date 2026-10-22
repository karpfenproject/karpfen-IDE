plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * A session exposes protocol types, such as the environment lifecycle state and the messages, and is created from the
 * environment descriptor of a workspace, so both modules are part of the API.
 */
dependencies {
    api(project(":runtime:runtime-protocol"))
    api(project(":core:core-workspace"))
}
