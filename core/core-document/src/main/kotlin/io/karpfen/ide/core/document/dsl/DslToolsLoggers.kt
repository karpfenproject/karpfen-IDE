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
package io.karpfen.ide.core.document.dsl

/**
 * The loggers of karpfen-dsl-tools. dsl-tools logs through SLF4J and names its loggers after its classes, so every one
 * of them lies below one parent logger, [PARENT]. Of what the IDE calls, only the readers log, and only a read that
 * fails ([DslReader] says how); the converters, which log what they convert at DEBUG, and the file parsers, which log
 * syntax errors as warnings, are not used by the IDE.
 *
 * The applications route this logging by the parent's name: the IDE keeps it off its console and shows it in its
 * Language Tools Output, and the command-line tool keeps it off unless asked for with `--verbose`. Their logging
 * configurations name the same logger, and their tests check that both agree with [PARENT].
 *
 * dsl-tools logs a read without naming the text it reads. The project analysis therefore puts the path of the file it
 * hands a reader into the mapped diagnostic context of SLF4J under [FILE_KEY] for the length of the read, so every event
 * logged during the read carries it: the Language Tools Output shows it, and the logging configurations write it.
 */
public object DslToolsLoggers {
    /** The name of the parent logger of every logger of dsl-tools. */
    public const val PARENT: String = "io.karpfen.dsl"

    /** The key under which the mapped diagnostic context names the file that dsl-tools reads, by its path. */
    public const val FILE_KEY: String = "karpfen.file"

    /** True if [loggerName] names [PARENT] or a logger below it. */
    public fun isDslToolsLogger(loggerName: String): Boolean = loggerName == PARENT || loggerName.startsWith("$PARENT.")
}
