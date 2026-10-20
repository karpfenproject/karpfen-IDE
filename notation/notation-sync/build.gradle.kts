plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // The synchronizer exposes the diagram view-model.
    api(project(":notation:notation-diagram"))
}
