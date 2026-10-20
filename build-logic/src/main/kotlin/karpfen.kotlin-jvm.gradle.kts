import io.karpfen.ide.buildlogic.ModuleDependencyRules
import io.karpfen.ide.buildlogic.VerifyModuleDependencies
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Base convention for every karpfen IDE module: Kotlin/JVM on JDK 21, JUnit Platform tests,
// explicit API mode for the UI-agnostic layers, and the module dependency rule check (D7).

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

group = "io.karpfen.ide"
version = "0.1.0-SNAPSHOT"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(21)
    // core/* and notation/* are the reusable, UI-agnostic layers: every public declaration is deliberate.
    if (path.startsWith(":core:") || path.startsWith(":notation:")) {
        explicitApi()
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findBundle("testing").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// --- module dependency rules ---------------------------------------------------------------------
// The project's own declared project dependencies are read when the task graph is built (the provider is
// evaluated at configuration time / when the configuration cache entry is stored) and handed to the task
// as plain strings. No other project is accessed.
val projectDependenciesOfThisModule: Provider<List<String>> = providers.provider {
    configurations
        .filter { ModuleDependencyRules.isDependencyScope(it.name) }
        .flatMap { configuration ->
            configuration.dependencies.withType<ProjectDependency>().map { dependency ->
                ModuleDependencyRules.DeclaredDependency(configuration.name, dependency.path).encode()
            }
        }
        .sorted()
}

val modulePath: String = path

val verifyModuleDependencies = tasks.register<VerifyModuleDependencies>("verifyModuleDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the declared project dependencies against the module dependency matrix (D7)."
    projectPath = modulePath
    declaredProjectDependencies = projectDependenciesOfThisModule
    reportFile = layout.buildDirectory.file("reports/module-dependencies/$name.txt")
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyModuleDependencies)
}
