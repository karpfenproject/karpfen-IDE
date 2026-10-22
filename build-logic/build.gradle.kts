plugins {
    `kotlin-dsl`
}

/**
 * The plugins that the karpfen.* convention plugins apply, and JUnit for the tests of the module dependency rules. The
 * plugin versions come from the shared version catalog, so the modules never declare plugin versions themselves.
 */
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.compose.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Runs the tests on the JUnit Platform without the time-budget tests, like every test task of the modules. The tag is
 * written out here because a build script cannot use the classes of its own build; it is the value of
 * `TestTags.BUDGET`, and the architecture test `BudgetTestTaggingTest` checks that every test task of the repository
 * excludes that tag. `VerifyModuleDependenciesFunctionalTest` builds a fixture with TestKit that reads the shared
 * version catalog relative to the working directory (this project's directory), so the catalog is a declared input and
 * a change to it reruns the tests.
 */
tasks.test {
    useJUnitPlatform {
        excludeTags("budget")
    }
    inputs.file(layout.projectDirectory.file("../gradle/libs.versions.toml"))
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("versionCatalog")
}
