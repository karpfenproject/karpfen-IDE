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
        }
    }
}
