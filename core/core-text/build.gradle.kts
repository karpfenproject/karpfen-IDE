plugins {
    id("karpfen.kotlin-jvm")
}

// Text buffer, TextEdit, offset <-> line/column, undo. EditableText publishes StateFlow/SharedFlow (spec §1, ADR 0009),
// so kotlinx-coroutines is part of the public API.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
