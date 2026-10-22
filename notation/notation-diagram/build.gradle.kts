plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * Diagram elements are keyed by the element identities of core-workspace, which are therefore part of the API. ELK
 * stays an implementation detail behind the auto-layout adapter.
 */
dependencies {
    api(project(":core:core-workspace"))
    implementation(libs.bundles.elk)
}
