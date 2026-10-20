plugins {
    `kotlin-dsl`
}

dependencies {
    // Plugins applied by the karpfen.* convention plugins. Their versions come from the shared catalog,
    // so the modules never declare plugin versions themselves.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.compose.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
