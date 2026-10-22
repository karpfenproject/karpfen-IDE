import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("karpfen.compose")
}

/**
 * The composition root depends on the workbench, which brings ui-editor, ui-foundation and core-lang (an `api` chain
 * down to core-text), and on logback, the logging backend, whose API the adapters of the `logging` package implement to
 * route what dsl-tools logs into the workbench's Language Tools Output. Test-only: the CLI, because the end-to-end tests
 * show that the Problems view, the status bar and `karpfen validate` agree on one folder.
 */
dependencies {
    implementation(project(":ui:ui-workbench"))
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:core-document")))
    testImplementation(project(":tools:cli"))
}

/**
 * The desktop application and its native packages.
 *
 * `run`, jlink/jpackage and the runtime bundled into the app image come from `javaHome`, which defaults to the JDK that
 * runs Gradle, not to the toolchain. Gradle may run on JDK 17, but the classes target the JDK 21 toolchain
 * (`karpfen.kotlin-jvm`), so `javaHome` is pinned to that toolchain.
 *
 * The extra JDK modules of the jlinked runtime are the ones `./gradlew :app:ide:suggestRuntimeModules` reports beyond
 * the default set; re-run it when adding libraries.
 *
 * jpackage on macOS turns the version into `CFBundleVersion` and rejects a MAJOR of 0 ("the first number in app-version
 * cannot be zero or negative"); the Compose plugin does not check this. 0.1.0 stays the version for Msi and Deb; the
 * macOS app image and dmg use 1.0.0 until the product reaches 1.x.
 */
compose.desktop {
    application {
        mainClass = "io.karpfen.ide.app.MainKt"
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "karpfen-ide"
            packageVersion = "0.1.0"
            modules("java.instrument", "jdk.unsupported")
            macOS {
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
            }
        }
    }
}
