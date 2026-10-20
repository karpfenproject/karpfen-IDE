plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // The workspace hands out documents and their snapshots.
    api(project(":core:core-document"))
}
