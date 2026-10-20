plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // Language services take workspace/document types as parameters.
    api(project(":core:core-workspace"))
}
