package io.karpfen.ide.core.workspace.environment

import java.nio.file.Path

/** [EnvironmentDescriptorReader] on kotlinx.serialization JSON (lenient about unknown keys, which become warnings). */
internal class JsonEnvironmentDescriptorReader : EnvironmentDescriptorReader {
    override fun read(path: Path, text: String): DescriptorReadResult =
        TODO("WP-WORKSPACE: decode the descriptor and locate keys and file references")
}
