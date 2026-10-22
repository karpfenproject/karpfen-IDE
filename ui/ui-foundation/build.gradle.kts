plugins {
    id("karpfen.compose")
}

dependencies {
    /**
     * Part of the public API: `Command.isEnabled` and `CommandRegistry.commands` are `StateFlow`s, because flows are
     * the IDE's reactive backbone.
     */
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
