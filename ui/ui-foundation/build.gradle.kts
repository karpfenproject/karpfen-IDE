plugins {
    id("karpfen.compose")
}

dependencies {
    // Command.isEnabled and CommandRegistry.commands are StateFlows (spec §6, ADR 0009).
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
