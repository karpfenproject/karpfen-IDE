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
package io.karpfen.ide.core.document.fixtures

import org.slf4j.MDC

/**
 * KStates documents that nest deep, and threads with a chosen stack size, which decides how deep a recursive parser can
 * follow. The parsers of the karpfen languages descend once per nesting level, so a text runs a parser out of stack when
 * it nests deep enough for the stack of the thread that parses it, a depth that changes with the state of the JIT
 * compiler. Texts deeper than the nesting limit of the IDE's parsers, `NestingLimit.MAX_DEPTH`, are not parsed at all;
 * within it, [guardedKstates] of a few hundred levels runs a reader of dsl-tools out of stack on [SMALL_STACK], and
 * every text stays far below what a parser follows on [LARGE_STACK].
 */
public object DeepNesting {

    /**
     * A stack, the smallest a thread gets, on which the readers of dsl-tools cannot follow even half the nesting the
     * limit allows: they fail from about 120 nested `IF IN SCOPE` blocks of [guardedKstates] on it, cold or warm.
     */
    public const val SMALL_STACK: Long = 128L * 1024

    /** A stack on which a parser of the karpfen languages follows tens of thousands of nesting levels. */
    public const val LARGE_STACK: Long = 32L * 1024 * 1024

    /** How many levels of braces of a [kstates] document enclose its states: the state machine and its states block. */
    public const val STRUCTURE_DEPTH: Int = 2

    /**
     * How many levels of braces of a [guardedKstates] document enclose its guards: the state machine, its states block,
     * the initial state and its `DO` block.
     */
    public const val GUARDS_STRUCTURE_DEPTH: Int = 4

    /**
     * A syntactically valid KStates document attached to [attachedTo] whose states `s1` to `s<depth>` nest [depth] levels
     * deep, after the initial state `s0`. Its braces nest [STRUCTURE_DEPTH] levels deeper than its states, those of the
     * state machine and of its states block, so the opening brace of state `s<n>` opens level `n + STRUCTURE_DEPTH`.
     */
    public fun kstates(depth: Int, attachedTo: String = "T"): String = buildString {
        append("STATEMACHINE ATTACHED TO \"").append(attachedTo).append("\" {\n")
        append("    STATES {\n")
        append("        INITIAL STATE \"s0\" { }\n")
        for (level in 1..depth) append("STATE \"s").append(level).append("\" {\n")
        repeat(depth) { append("}\n") }
        append("    }\n")
        append("    TRANSITIONS { }\n")
        append("}\n")
    }

    /**
     * A syntactically valid KStates document attached to [attachedTo] whose initial state runs an action nested in
     * [depth] `IF IN SCOPE` blocks, the nesting that costs a reader of dsl-tools the most stack per level. Its braces
     * nest [GUARDS_STRUCTURE_DEPTH] levels deeper than its guards.
     */
    public fun guardedKstates(depth: Int, attachedTo: String = "T"): String = buildString {
        append("STATEMACHINE ATTACHED TO \"").append(attachedTo).append("\" {\n")
        append("    STATES {\n")
        append("        INITIAL STATE \"s0\" {\n")
        append("            DO {\n")
        repeat(depth) { append("IF IN SCOPE(\"a\") {\n") }
        append("SET(\"x\", \"1\")\n")
        repeat(depth) { append("}\n") }
        append("            }\n")
        append("        }\n")
        append("    }\n")
        append("    TRANSITIONS { }\n")
        append("}\n")
    }

    /**
     * Runs [block] on a new thread with a stack of [stackBytes] and returns its result; what [block] throws is thrown
     * again on the calling thread, which waits until the new one ends. The new thread starts with the mapped diagnostic
     * context of the logging of the calling thread, so what is logged on it names what the caller names, such as the
     * file a reader of dsl-tools reads.
     */
    public fun <T> onStack(stackBytes: Long, block: () -> T): T {
        val logging: Map<String, String>? = MDC.getCopyOfContextMap()
        var result: Result<T>? = null
        val run = {
            logging?.let(MDC::setContextMap)
            result = runCatching(block)
        }
        val thread = Thread(null, run, "stack-${stackBytes / 1024}k", stackBytes)
        thread.start()
        thread.join()
        return checkNotNull(result) { "the thread ended without a result" }.getOrThrow()
    }
}
