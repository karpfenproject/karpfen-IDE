plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // A session exposes protocol types (lifecycle state, messages) and is created from a workspace's
    // environment descriptor.
    api(project(":runtime:runtime-protocol"))
    api(project(":core:core-workspace"))
}
