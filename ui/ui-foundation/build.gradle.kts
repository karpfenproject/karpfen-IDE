plugins {
    id("karpfen.compose")
}

/**
 * kotlinx-coroutines is part of the public API: `Command.isEnabled` and `CommandRegistry.commands` are `StateFlow`s,
 * because flows are the IDE's reactive backbone.
 */
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
