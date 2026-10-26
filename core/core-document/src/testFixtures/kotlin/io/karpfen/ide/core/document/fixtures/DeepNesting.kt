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

/**
 * KStates documents that nest states deeper than a recursive parser can follow, and threads with a chosen stack size,
 * which decides how deep that is. The parsers of the karpfen languages descend once per nesting level, so a text runs a
 * parser out of stack when it nests deep enough for the stack of the thread that parses it: [kstates] of a few thousand
 * levels does so on [SMALL_STACK], and stays far below the limit on [LARGE_STACK].
 */
public object DeepNesting {

    /** A stack on which a parser of the karpfen languages cannot follow a few hundred nesting levels. */
    public const val SMALL_STACK: Long = 256L * 1024

    /** A stack on which a parser of the karpfen languages follows tens of thousands of nesting levels. */
    public const val LARGE_STACK: Long = 32L * 1024 * 1024

    /**
     * A syntactically valid KStates document attached to [attachedTo] whose states `s1` to `s<depth>` nest [depth] levels
     * deep, after the initial state `s0`.
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
     * Runs [block] on a new thread with a stack of [stackBytes] and returns its result; what [block] throws is thrown
     * again on the calling thread, which waits until the new one ends.
     */
    public fun <T> onStack(stackBytes: Long, block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "stack-${stackBytes / 1024}k", stackBytes)
        thread.start()
        thread.join()
        return checkNotNull(result) { "the thread ended without a result" }.getOrThrow()
    }
}
