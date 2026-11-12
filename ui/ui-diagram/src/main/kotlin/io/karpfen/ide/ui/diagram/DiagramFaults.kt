/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.diagram

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

/**
 * Keeps the diagram view alive when something in it fails. An exception while a diagram is measured, laid out, drawn
 * or changed by a gesture would otherwise leave the composition of the whole window, which then stops reacting. A
 * diagram that is partly drawn, or not drawn at all, is the far smaller harm.
 *
 * Every step of the view that runs code of a notation goes through [guard]. A step that fails is skipped or replaced
 * by a fallback, the failure is logged with its stack trace, and [note] tells the user about it over the drawing.
 * Only the first failure is shown, because later ones usually follow from it. Each failure is logged once, so a step
 * that fails in every frame does not flood the log.
 *
 * One instance belongs to one diagram on screen. A new text gives a new diagram and a fresh instance, so the note
 * goes away once the cause is gone. The object belongs to the UI thread.
 */
internal class DiagramFaults {

    /** What the view tells the user about the first failure, or null as long as nothing failed. */
    var note: String? by mutableStateOf(null)
        private set

    private val logged = HashSet<String>()

    /**
     * Runs [block] and returns its result. If it fails, the failure is reported as having happened while [doing], and
     * the result of [fallback] is returned instead. [doing] continues the sentence "Something went wrong while".
     *
     * Exceptions and a stack overflow count as failures. The cancellation of a coroutine is passed on, and so are
     * the other errors of the virtual machine, which no fallback can mend.
     */
    inline fun <T> guard(doing: String, fallback: () -> T, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(doing, e)
            fallback()
        } catch (e: StackOverflowError) {
            report(doing, e)
            fallback()
        }

    /** Runs [block]. If it fails, the failure is reported as having happened while [doing], and nothing else happens. */
    inline fun attempt(doing: String, block: () -> Unit) {
        guard(doing, {}, block)
    }

    /** Logs that [error] happened while [doing], once, and shows it in the [note] if it is the first failure. */
    fun report(doing: String, error: Throwable) {
        val text = "Something went wrong while $doing: ${describe(error)}. The diagram shows what could be drawn."
        if (logged.add(text)) log.warn("The diagram view failed while {}", doing, error)
        if (note == null) note = text
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiagramFaults::class.java)

        /** [error] in a few words: its kind, and its message if it has one. */
        fun describe(error: Throwable): String {
            val kind = error::class.simpleName ?: "error"
            return error.message?.takeIf { it.isNotBlank() }?.let { "$kind, $it" } ?: kind
        }
    }
}
