/**
 * Convention for modules with kotlinx.serialization, such as the data transfer objects of the runtime protocol: the
 * Kotlin/JVM base convention plus the serialization compiler plugin and the JSON runtime. It has to be a convention
 * plugin because the serialization plugin reaches the modules through build-logic's classpath and therefore cannot be
 * requested by id alone in a module's `plugins` block.
 */
plugins {
    id("karpfen.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(libs.findLibrary("kotlinx-serialization-json").get())
}
