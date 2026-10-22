/**
 * Convention for the modules built with Compose Multiplatform for Desktop, the UI toolkit of the IDE: the Kotlin/JVM
 * base convention, the Compose Gradle plugin and the Compose compiler plugin, with Compose Foundation, Material 3 and
 * the Compose UI test library.
 *
 * Compose is confined to the UI layer so that every other module stays UI-agnostic: only the `:ui:` and `:app:`
 * modules may apply this plugin, which the check below enforces, and the architecture tests reject Compose, AWT and
 * Swing everywhere else.
 */
plugins {
    id("karpfen.kotlin-jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

check(path.startsWith(":ui:") || path.startsWith(":app:")) {
    "karpfen.compose may only be applied to ui and app modules (every other module is UI-agnostic), not $path"
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.findLibrary("compose-foundation").get())
    implementation(libs.findLibrary("compose-material3").get())

    testImplementation(libs.findLibrary("compose-ui-test").get())
}

/**
 * Runs the tests headless: Compose UI tests render off-screen through Skia and need no display, so they also run on CI
 * and on headless servers.
 */
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
