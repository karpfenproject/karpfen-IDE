plugins {
    id("karpfen.kotlin-jvm")
}

dependencies {
    // The mapping engine implements the Mapping SPI of bridge-api.
    api(project(":bridges:bridge-api"))
}
