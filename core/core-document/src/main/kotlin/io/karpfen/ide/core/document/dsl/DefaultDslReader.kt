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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.TextReader
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.text.TextSnapshot
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Reads with the dsl-tools readers it is given and turns what they report into the IDE's values with [ReadResults] (see
 * [DslReader] for the contract). The `DslReader` factory function wires the readers of dsl-tools.
 *
 * The readers never throw by their own contract: they turn runtime exceptions and the `StackOverflowError` of deeply
 * nested input into a `reader.failure` diagnostic themselves. Any other throwable that escapes a reader, except a fatal
 * VM error, is a defect of dsl-tools; it is logged and becomes the same `reader.failure` diagnostic, so the IDE keeps
 * running.
 *
 * Stateless and thread-safe as long as its readers keep no state between reads, which the readers of dsl-tools do not.
 *
 * @param kmetaReader the KMeta reader over the given base types, created for each read.
 * @param kmodelReader the KModel reader against the given metamodel, created for each read.
 * @param kstatesReader the KStates reader, shared by every read.
 */
internal class DefaultDslReader(
    private val kmetaReader: (baseTypes: List<ClassType>) -> TextReader<Metamodel>,
    private val kmodelReader: (metamodel: Metamodel) -> TextReader<Model>,
    private val kstatesReader: TextReader<StateMachine>,
) : DslReader {

    override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>): ReadResult<Metamodel> =
        read(source, kmetaReader(baseTypes))

    override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> =
        read(source, kmodelReader(metamodel))

    override fun kstates(source: TextSnapshot): ReadResult<StateMachine> = read(source, kstatesReader)

    /** Reads [source] with [reader]; a throwable that escapes it, except a fatal VM error, becomes a reader failure. */
    private fun <T : Any> read(source: TextSnapshot, reader: TextReader<T>): ReadResult<T> =
        try {
            ReadResults.of(reader.read(source.text), source)
        } catch (e: StackOverflowError) {
            failed(source, e)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            failed(source, e)
        }

    /** Logs [failure] and returns the [ReadResults.failure] it stands for. */
    private fun failed(source: TextSnapshot, failure: Throwable): ReadResult<Nothing> {
        logger.error("A dsl-tools reader failed on a text of version {}", source.version, failure)
        return ReadResults.failure(failure)
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(DefaultDslReader::class.java)
    }
}
