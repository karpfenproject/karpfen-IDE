import io.karpfen.ide.buildlogic.ModuleDependencyRules
import io.karpfen.ide.buildlogic.ResolvedProjectDependencies
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
// The task gets two plain string lists, so it never touches the project model and no other project is accessed:
//
// 1. Declared project dependencies of every configuration, not only api/implementation/compileOnly/runtimeOnly and
//    their test variants: a project dependency declared on a custom configuration that a classpath extends from, or
//    on annotationProcessor, kapt or ksp, is checked as well. (`dependencies` holds what is declared on that
//    configuration itself, so inherited declarations are not reported twice.) The scan runs in projectsEvaluated,
//    i.e. after every afterEvaluate callback, including those of the module's own build script.
// 2. First-level project dependencies of the resolved classpaths of every source set, as providers of the resolution
//    result. They are evaluated when the configuration cache entry is stored (or when the task runs without the
//    configuration cache), so they also see dependencies added at resolution time (withDependencies,
//    defaultDependencies) and any declaration that the scan in 1. did not see.
val modulePath: String = path

val verifyModuleDependencies = tasks.register<VerifyModuleDependencies>("verifyModuleDependencies") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the project dependencies against the module dependency matrix (D7)."
    projectPath = modulePath
    reportFile = layout.buildDirectory.file("reports/module-dependencies/$name.txt")
}

gradle.projectsEvaluated {
    val declaredProjectDependencies: List<String> = configurations
        .flatMap { configuration ->
            configuration.dependencies.withType<ProjectDependency>().map { dependency ->
                ModuleDependencyRules.DeclaredDependency(configuration.name, dependency.path).encode()
            }
        }
        .sorted()
    verifyModuleDependencies.configure {
        this.declaredProjectDependencies.set(declaredProjectDependencies)
    }
}

sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { classpath ->
        val firstLevelProjectDependencies: Provider<List<String>> = configurations.named(classpath)
            .flatMap { it.incoming.resolutionResult.rootComponent }
            .map { root -> ResolvedProjectDependencies.firstLevel(classpath, root) }
        verifyModuleDependencies.configure {
            resolvedProjectDependencies.addAll(firstLevelProjectDependencies)
        }
    }
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyModuleDependencies)
}
