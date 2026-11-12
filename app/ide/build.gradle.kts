/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("karpfen.compose")
}

/**
 * The composition root depends on the workbench, which brings ui-editor, ui-foundation and core-lang through a chain
 * of `api` dependencies down to core-text. It also depends on logback, the logging backend. The adapters of the
 * `logging` package implement logback's API to route what dsl-tools logs into the workbench's Language Tools Output.
 * The CLI is a test dependency only, because the end-to-end tests show that the Problems view, the status bar and
 * `karpfen validate` agree on one folder.
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
 * the default set. Run that task again when you add libraries.
 *
 * jpackage on macOS turns the version into `CFBundleVersion` and rejects a major version of 0 with "the first number
 * in app-version cannot be zero or negative". The Compose plugin does not check this. So 0.1.0 stays the version for
 * Msi and Deb, and the macOS app image and the dmg use 1.0.0 until the product reaches 1.x.
 *
 * On macOS the Dock names a JVM "java" unless the `-Xdock:name` option on its command line says otherwise. A system
 * property set later cannot change that. So `run` and the packaged application get the option on macOS. Other
 * platforms do not know the option, and their JVM would refuse to start with it.
 *
 * Each platform takes the application's icon in its own format. The icns and ico files under `src/main/packaging` are
 * made from the two PNG files among the resources. The icns file has the shape of a Mac application icon, like the
 * image the running IDE hands to the Dock. The ico file and the Linux package use the plain square image.
 */
compose.desktop {
    application {
        mainClass = "io.karpfen.ide.app.MainKt"
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
        if (System.getProperty("os.name").startsWith("Mac")) jvmArgs += "-Xdock:name=Karpfen IDE"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "karpfen-ide"
            packageVersion = "0.1.0"
            modules("java.instrument", "jdk.unsupported")
            macOS {
                packageVersion = "1.0.0"
                dmgPackageVersion = "1.0.0"
                iconFile.set(layout.projectDirectory.file("src/main/packaging/karpfen-ide.icns"))
            }
            windows {
                iconFile.set(layout.projectDirectory.file("src/main/packaging/karpfen-ide.ico"))
            }
            linux {
                iconFile.set(layout.projectDirectory.file("src/main/resources/icons/karpfen-ide.png"))
            }
        }
    }
}

/**
 * Starts the IDE like `run`, also with `--args=<folder>`, but with readers of dsl-tools that read on a small stack. A
 * state machine nested a few hundred levels deep then makes them fail as a real reader failure does. This is how a
 * hands-on check fills the Language Tools Output, through `FailingReadersLaunch` of the tests.
 *
 * The task runs on the classpath of the tests. It sets their console-only `logback-test.xml` aside for the production
 * `logback.xml` of this module. That file is named by its URL, because the classpath holds the command-line tool's
 * `logback.xml` too, and a name that two resources answer makes logback print its status on the console.
 */
tasks.register<JavaExec>("runWithFailingReaders") {
    group = "application"
    description = "Runs the IDE with dsl-tools readers that fail on deeply nested state machines (manual smoke test)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.karpfen.ide.app.FailingReadersLaunchKt"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    val productionConfiguration = layout.projectDirectory.file("src/main/resources/logback.xml").asFile.toURI()
    systemProperty("logback.configurationFile", productionConfiguration.toString())
}
