// Compose Multiplatform for Desktop (D4). Only ui/* and app/ide may apply this plugin;
// the architecture tests reject Compose imports everywhere else.

plugins {
    id("karpfen.kotlin-jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

check(path.startsWith(":ui:") || path.startsWith(":app:")) {
    "karpfen.compose may only be applied to ui/* and app/* modules (docs/adr/0005-compose-multiplatform-for-desktop.md), not $path"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.findLibrary("compose-foundation").get())
    implementation(libs.findLibrary("compose-material3").get())

    testImplementation(libs.findLibrary("compose-ui-test").get())
}

tasks.withType<Test>().configureEach {
    // Compose UI tests render off-screen through Skia; no display is needed (CI, headless servers).
    systemProperty("java.awt.headless", "true")
}
