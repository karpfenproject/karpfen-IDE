plugins {
    id("karpfen.kotlin-jvm")
}

/** The declarative mapping engine implements the mapping SPI of bridge-api, which is therefore part of its API. */
dependencies {
    api(project(":bridges:bridge-api"))
}
