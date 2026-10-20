// karpfen.kotlin-jvm + kotlinx.serialization (compiler plugin and JSON runtime), e.g. for runtime DTOs.
// A convention plugin is needed because the serialization plugin reaches the modules through build-logic's
// classpath and therefore cannot be requested by id alone in a module's plugins block.

plugins {
    id("karpfen.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
}
