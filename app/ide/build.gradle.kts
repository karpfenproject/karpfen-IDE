import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("karpfen.compose")
}

dependencies {
    implementation(project(":ui:ui-workbench"))
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "io.karpfen.ide.app.MainKt"
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
