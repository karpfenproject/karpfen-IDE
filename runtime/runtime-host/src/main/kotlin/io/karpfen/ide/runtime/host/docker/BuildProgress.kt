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
package io.karpfen.ide.runtime.host.docker

/**
 * The progress of an image build as one short line for the status. Once the build reports steps, the line is the
 * latest `Step x/y` of the build output. Before that it is the latest output line, shortened to [MAX_LENGTH]
 * characters. It is fed from the thread that reads the build output and is not safe for concurrent use.
 */
internal class BuildProgress {
    /** The latest step the build reported, or null before the first one. */
    private var step: String? = null

    /** The status line after the build printed [line]. */
    fun after(line: String): String {
        STEP.find(line)?.let { match -> step = "Step ${match.groupValues[1]}/${match.groupValues[2]}" }
        return step ?: shorten(line.trim())
    }

    /** [text] cut to [MAX_LENGTH] characters, ending in an ellipsis if it was longer. */
    private fun shorten(text: String): String = if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - 1) + "…"

    private companion object {
        /** The longest status line. */
        const val MAX_LENGTH = 80

        /** A step line of the classic builder, such as `Step 7/14 : RUN ./gradlew installDist`. */
        val STEP = Regex("""^\s*Step (\d+)/(\d+)\b""")
    }
}
