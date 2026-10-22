/** The root project has no code: every module applies one of the karpfen.* convention plugins from build-logic. */
plugins {
    base
}

/**
 * Makes `./gradlew build` also run the tests of the convention plugins, among them the tests of the module dependency
 * matrix.
 */
tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
