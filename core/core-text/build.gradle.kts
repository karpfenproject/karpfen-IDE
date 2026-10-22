plugins {
    id("karpfen.kotlin-jvm")
}

/**
 * The text buffer: edits, offset to line/column mapping and undo history. `EditableText` publishes its snapshots and
 * changes as `StateFlow` and `SharedFlow`, because coroutine flows are the reactive backbone of the IDE, so
 * kotlinx-coroutines is part of this module's public API.
 */
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
