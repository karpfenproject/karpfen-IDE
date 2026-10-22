plugins {
    id("karpfen.kotlin-jvm")
}

/** The synchronizer exposes the diagram view model of notation-diagram. */
dependencies {
    api(project(":notation:notation-diagram"))
}
