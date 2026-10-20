package io.karpfen.ide.app

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay

/** Set to a number of milliseconds to let the IDE close itself and exit 0 (launch smoke tests). */
private const val EXIT_AFTER_MS_ENV: String = "KARPFEN_IDE_EXIT_AFTER_MS"

/** Entry point of the karpfen IDE desktop application (phase-0 stub: an empty window). */
public fun main() {
    val exitAfterMs = System.getenv(EXIT_AFTER_MS_ENV)?.let { value ->
        value.trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: run {
                System.err.println("Ignoring $EXIT_AFTER_MS_ENV='$value': not a non-negative number of milliseconds")
                null
            }
    }

    application {
        if (exitAfterMs != null) {
            LaunchedEffect(Unit) {
                delay(exitAfterMs)
                exitApplication()
            }
        }
        Window(onCloseRequest = ::exitApplication, title = "karpfen IDE") {
            Text("karpfen IDE")
        }
    }
}
