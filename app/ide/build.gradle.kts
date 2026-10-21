import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("karpfen.compose")
}

dependencies {
    // Composition root: the workbench brings ui-editor, ui-foundation and core-lang (api chain down to core-text).
    implementation(project(":ui:ui-workbench"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
    // Test-only: the logging-configuration tests read logback's LoggerContext (rule 7 of the work-package plan).
    testImplementation(libs.logback.classic)
    // Test-only (INTEGRATION's e2e): the Problems view, the status bar and `karpfen validate` agree on one folder.
    testImplementation(project(":tools:cli"))
}

compose.desktop {
    application {
        mainClass = "io.karpfen.ide.app.MainKt"
        // `run`, jlink/jpackage and the runtime bundled into the app image come from javaHome, which defaults to the
        // JDK that runs Gradle, not to the toolchain. Gradle may run on JDK 17, but the classes target the JDK 21
        // toolchain (karpfen.kotlin-jvm), so pin javaHome to that toolchain.
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "karpfen-ide"
            packageVersion = "0.1.0"
            // JDK modules for the jlinked runtime beyond the default set, as reported by
            // `./gradlew :app:ide:suggestRuntimeModules`. Re-run it when adding libraries.
            modules("java.instrument", "jdk.unsupported")
            macOS {
                // jpackage on macOS turns the version into CFBundleVersion and rejects a MAJOR of 0 ("the first number
                // in app-version cannot be zero or negative"); the Compose plugin does not check this. 0.1.0 stays the
                // version for Msi/Deb; the macOS app image and dmg use 1.0.0 until the product reaches 1.x.
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
        }
    }
}
