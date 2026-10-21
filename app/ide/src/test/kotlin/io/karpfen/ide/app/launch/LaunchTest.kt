package io.karpfen.ide.app.launch

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.settings.SettingsLocations
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What `main` resolves before the application starts: home directory, launch options, desktop properties. */
class LaunchTest {

    @TempDir
    lateinit var dir: Path

    private val properties = mutableMapOf<String, String>()

    @Test
    fun `KARPFEN_IDE_HOME becomes the absolute home property that settings and log read`() {
        val environment = mapOf(SettingsLocations.HOME_ENVIRONMENT_VARIABLE to " ${dir.resolve("a/../home")} ")

        val result = applyHomeOverride(environment::get, properties::put)

        val home = dir.resolve("home").toAbsolutePath().normalize()
        assertEquals(HomeOverride(home), result)
        assertEquals(mapOf(SettingsLocations.HOME_PROPERTY to home.toString()), properties)
    }

    @Test
    fun `a relative KARPFEN_IDE_HOME is resolved against the working directory`() {
        val result = applyHomeOverride(mapOf(SettingsLocations.HOME_ENVIRONMENT_VARIABLE to "tmp-home")::get, properties::put)

        assertEquals(Path.of("tmp-home").toAbsolutePath().normalize(), result.home)
        assertTrue(Path.of(properties.getValue(SettingsLocations.HOME_PROPERTY)).isAbsolute)
    }

    @Test
    fun `an unset or blank KARPFEN_IDE_HOME leaves the property alone`() {
        assertEquals(HomeOverride(null), applyHomeOverride({ null }, properties::put))
        assertEquals(HomeOverride(null), applyHomeOverride(mapOf(SettingsLocations.HOME_ENVIRONMENT_VARIABLE to "  ")::get, properties::put))
        assertEquals(emptyMap(), properties)
    }

    @Test
    fun `an unusable KARPFEN_IDE_HOME is ignored with a warning`() {
        val result = applyHomeOverride(mapOf(SettingsLocations.HOME_ENVIRONMENT_VARIABLE to "bad\u0000home")::get, properties::put)

        assertNull(result.home)
        assertTrue(result.warning!!.startsWith("Ignoring KARPFEN_IDE_HOME="), result.warning)
        assertEquals(emptyMap(), properties)
    }

    @Test
    fun `without arguments the IDE starts empty and does not exit by itself`() {
        assertEquals(LaunchOptions(null, null), LaunchOptions.parse(emptyList(), { null }))
    }

    @Test
    fun `the folder argument is opened as an absolute normalized path`() {
        val options = LaunchOptions.parse(listOf(dir.resolve("x/..").toString()), { null })

        assertEquals(LaunchOptions(dir.toAbsolutePath().normalize(), null), options)
    }

    @Test
    fun `a folder argument that is not a directory and extra arguments are ignored with warnings`() {
        val file = dir.resolve("robot.kmeta").also { it.toFile().writeText("") }

        val options = LaunchOptions.parse(listOf(file.toString(), "second", "third"), { null })

        assertNull(options.initialFolder)
        assertEquals(2, options.warnings.size)
        assertTrue(options.warnings[0].startsWith("Ignoring extra arguments [second, third]"), options.warnings[0])
        assertTrue(options.warnings[1].endsWith("is not a directory"), options.warnings[1])
    }

    @Test
    fun `the exit delay comes from KARPFEN_IDE_EXIT_AFTER_MS, invalid values are ignored with a warning`() {
        fun parse(value: String) = LaunchOptions.parse(emptyList(), mapOf(LaunchOptions.EXIT_AFTER_MS_ENVIRONMENT_VARIABLE to value)::get)

        assertEquals(6000L, parse(" 6000 ").exitAfterMs)
        assertEquals(0L, parse("0").exitAfterMs)
        listOf("-1", "soon", "").forEach { value ->
            val options = parse(value)
            assertNull(options.exitAfterMs, value)
            assertEquals(
                listOf("Ignoring KARPFEN_IDE_EXIT_AFTER_MS='$value': not a non-negative number of milliseconds"),
                options.warnings,
            )
        }
    }

    @Test
    fun `on macOS the menu bar moves into the screen menu bar, an explicit property wins`() {
        val existing = mapOf("apple.awt.application.name" to "Custom")

        applyDesktopProperties(HostPlatform.MAC_OS, existing::get, properties::put)

        assertEquals(mapOf("apple.laf.useScreenMenuBar" to "true", "apple.eawt.quitStrategy" to "CLOSE_ALL_WINDOWS"), properties)
        assertEquals(APPLICATION_NAME, desktopProperties(HostPlatform.MAC_OS)["apple.awt.application.name"])
    }

    @Test
    fun `on macOS Quit closes the windows instead of exiting, so it runs the close request's unsaved-changes flow`() {
        // AWT's default quit strategy on macOS calls System.exit(0) for Cmd+Q, skipping the window's close request.
        assertEquals("CLOSE_ALL_WINDOWS", desktopProperties(HostPlatform.MAC_OS)["apple.eawt.quitStrategy"])

        applyDesktopProperties(HostPlatform.MAC_OS, mapOf("apple.eawt.quitStrategy" to "NORMAL_EXIT")::get, properties::put)

        assertNull(properties["apple.eawt.quitStrategy"], "an explicit -Dapple.eawt.quitStrategy wins")
    }

    @Test
    fun `other platforms set no desktop properties`() {
        applyDesktopProperties(HostPlatform.WINDOWS, { null }, properties::put)
        applyDesktopProperties(HostPlatform.LINUX, { null }, properties::put)

        assertEquals(emptyMap(), properties)
    }
}
