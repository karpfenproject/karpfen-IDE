package io.karpfen.ide.app

import io.karpfen.ide.ui.foundation.keys.HostPlatform
import io.karpfen.ide.ui.workbench.state.WorkbenchServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/** `IdeApplication.prepare`: all window-free wiring, headless with a fake workbench state (spec §9, decision 34). */
@OptIn(ExperimentalCoroutinesApi::class)
class IdeApplicationPrepareTest {

    private val folder: Path = Path.of("/work/robots").toAbsolutePath()

    private class Wiring(scope: TestScope, platform: HostPlatform = HostPlatform.LINUX) {
        val services = RecordingServicesFactory(platform, StandardTestDispatcher(scope.testScheduler))
        val states = mutableListOf<FakeWorkbenchState>()
        val stateScopes = mutableListOf<CoroutineScope>()
        val stateServices = mutableListOf<WorkbenchServices>()
        var configure: (FakeWorkbenchState) -> Unit = {}

        val application = IdeApplication(
            services = services,
            workbenchStateFactory = { scope, services ->
                stateScopes += scope
                stateServices += services
                FakeWorkbenchState(scope, services).also { configure(it); states += it }
            },
        )
    }

    @Test
    fun `prepare creates the services once and the state over them in the given scope`() = runTest {
        val wiring = Wiring(this)

        val session = wiring.application.prepare(backgroundScope, initialFolder = null)

        assertEquals(1, wiring.services.calls)
        val state = wiring.states.single()
        assertSame(state, session.state)
        assertSame(backgroundScope, wiring.stateScopes.single())
        assertSame(wiring.services.services, wiring.stateServices.single())
    }

    @Test
    fun `the command registry is created for the services' platform and filled by the state before it starts`() = runTest {
        val wiring = Wiring(this, platform = HostPlatform.LINUX)

        val session = wiring.application.prepare(backgroundScope, initialFolder = null)

        val state = wiring.states.single()
        assertSame(session.commands, state.registry)
        assertEquals(HostPlatform.LINUX, session.commands.platform, "the one platform source is WorkbenchServices.platform")
        assertNotNull(session.commands.find(FakeWorkbenchState.FAKE_COMMAND_ID))
        assertEquals(listOf("registerCommands", "start"), state.calls)
    }

    @Test
    fun `the initial folder is opened and its job joined before prepare returns`() = runTest {
        val wiring = Wiring(this)
        wiring.configure = { it.openFolderTime = 3.seconds }

        val session = wiring.application.prepare(backgroundScope, folder)

        val state = session.state as FakeWorkbenchState
        assertEquals(listOf("registerCommands", "start", "openFolder $folder"), state.calls)
        assertEquals(folder, state.openedFolder, "the openFolder job completed")
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `prepare is not done while the folder is still opening`() = runTest {
        val wiring = Wiring(this)
        wiring.configure = { it.openFolderTime = 3.seconds }

        val preparing = async { wiring.application.prepare(backgroundScope, folder) }
        runCurrent()

        assertEquals(false, preparing.isCompleted)
        assertNull(wiring.states.single().openedFolder)
        preparing.await()
    }

    @Test
    fun `a failing start closes the state and propagates`() = runTest {
        val wiring = Wiring(this)
        wiring.configure = { it.startFailure = IllegalStateException("settings unreadable") }

        val failure = assertFailsWith<IllegalStateException> { wiring.application.prepare(backgroundScope, folder) }

        assertEquals("settings unreadable", failure.message)
        assertEquals(listOf("registerCommands", "start", "close"), wiring.states.single().calls)
    }

    @Test
    fun `cancelling prepare while the folder opens closes the state`() = runTest {
        val wiring = Wiring(this)
        wiring.configure = { it.openFolderTime = 10.seconds }

        val preparing = async { wiring.application.prepare(backgroundScope, folder) }
        runCurrent()
        preparing.cancel()
        runCurrent()

        assertEquals("close", wiring.states.single().calls.last())
    }
}
