package io.karpfen.ide.ui.workbench.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import java.time.Clock

/** [SettingsRepository] storing `settings.json` with kotlinx.serialization (atomic write, corrupt-file backup). */
internal class JsonSettingsRepository(
    private val directory: Path,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : SettingsRepository {
    override val settings: StateFlow<IdeSettings>
        get() = TODO("WP-WORKBENCH: settings flow")

    override suspend fun load(): IdeSettings = TODO("WP-WORKBENCH: load settings.json")

    override suspend fun update(transform: (IdeSettings) -> IdeSettings): IdeSettings = TODO("WP-WORKBENCH: write settings.json")
}
