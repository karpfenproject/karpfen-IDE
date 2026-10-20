// Root project: no code. Every module applies one of the karpfen.* convention plugins from build-logic.

plugins {
    base
}

// `./gradlew build` also runs the tests of the convention plugins (module dependency matrix).
tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
