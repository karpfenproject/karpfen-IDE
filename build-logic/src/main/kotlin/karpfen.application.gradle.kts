/**
 * Convention for the headless command-line applications, tools/cli and bridges/bridge-runner: the Kotlin/JVM base
 * convention plus Gradle's `application` plugin. Each module sets `application.mainClass` itself.
 */
plugins {
    id("karpfen.kotlin-jvm")
    application
}
