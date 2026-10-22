package io.karpfen.ide.ui.workbench.output

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * An in-memory ring buffer of the output of the language tools: it keeps the newest [capacity] lines and drops the
 * oldest one for each line beyond. The logging adapter of the application writes to it as an [OutputSink], and the
 * workbench reads it as an [OutputBuffer]; the composition root creates one for the life of the application.
 *
 * Thread-safe: appends, snapshots and clearing hold one lock for as long as it takes to change or copy the lines, and
 * the [revision] is raised under that lock, so a reader that sees a revision also sees its lines.
 *
 * @throws IllegalArgumentException if [capacity] is not positive.
 */
public class RingOutputBuffer(private val capacity: Int = DEFAULT_CAPACITY) : OutputSink, OutputBuffer {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lock = Any()
    private val lines = ArrayDeque<OutputLine>()
    private var lastSequence = 0L
    private var errors = 0L
    private var warnings = 0L
    private val changes = MutableStateFlow(0L)

    override val revision: StateFlow<Long> get() = changes.asStateFlow()

    override fun append(time: Instant, level: OutputLevel, logger: String, message: String) {
        synchronized(lock) {
            lastSequence++
            when (level) {
                OutputLevel.ERROR -> errors++
                OutputLevel.WARNING -> warnings++
                else -> Unit
            }
            if (lines.size == capacity) lines.removeFirst()
            lines.addLast(OutputLine(lastSequence, time, level, logger, message))
            changes.value++
        }
    }

    override fun snapshot(): OutputSnapshot = synchronized(lock) { OutputSnapshot(lines.toList(), errors, warnings) }

    override fun clear() {
        synchronized(lock) {
            lines.clear()
            changes.value++
        }
    }

    public companion object {
        /** How many lines a buffer keeps unless told otherwise: enough for the model dumps of several analyses. */
        public const val DEFAULT_CAPACITY: Int = 5_000
    }
}
