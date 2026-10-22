package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.TextReader
import io.karpfen.dsl.reader.kmeta.KmetaReader
import io.karpfen.dsl.reader.kmodel.KmodelReader
import io.karpfen.dsl.reader.kstates.KstatesReader
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.text.TextSnapshot
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Reads with the dsl-tools readers and turns what they report into the IDE's values with [ReadResults] (see
 * [DslReader] for the contract).
 *
 * The readers never throw by their own contract: they turn runtime exceptions and the `StackOverflowError` of deeply
 * nested input into a `reader.failure` diagnostic themselves. Any other throwable that escapes a reader, except a fatal
 * VM error, is a defect of dsl-tools; it is logged and becomes the same `reader.failure` diagnostic, so the IDE keeps
 * running.
 *
 * Stateless and thread-safe.
 */
internal class DefaultDslReader : DslReader {

    private val kstatesReader = KstatesReader()

    override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>): ReadResult<Metamodel> =
        read(source, KmetaReader(baseTypes))

    override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> =
        read(source, KmodelReader(metamodel))

    override fun kstates(source: TextSnapshot): ReadResult<StateMachine> = read(source, kstatesReader)

    /**
     * Reads [source] with [reader]; a throwable that escapes it, except a fatal VM error, becomes a reader failure.
     * Internal rather than private so that the tests can hand it a reader that fails.
     */
    internal fun <T : Any> read(source: TextSnapshot, reader: TextReader<T>): ReadResult<T> =
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
