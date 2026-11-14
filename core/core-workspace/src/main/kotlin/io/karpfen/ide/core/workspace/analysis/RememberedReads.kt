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
package io.karpfen.ide.core.workspace.analysis

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.text.TextSnapshot
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The last read of each file of one kind of model. It is kept from one analysis of a workspace to the next, so that a
 * file is read again only when its text or the input it is read against changed. [read] answers with the remembered
 * result for the same text and the same input, and reads otherwise. So an unchanged file that a reader cannot read
 * fails, and is logged by dsl-tools, only once and not on every analysis.
 *
 * A read depends on nothing but its text and its input. So its result holds for any snapshot of the same text. Its
 * findings lie at offsets of that text, and the models are never changed after reading, as [ProjectAnalysis] states.
 * The input is the metamodel a model or the events are read against, or the set of language features a state machine
 * or a domain metamodel is read with, and it is compared by identity. That works because an unchanged metamodel is itself a remembered result and so the
 * same object, and the workspace hands the analyses the same set of features until the features change. An equal set
 * in a new object only costs one read too many.
 *
 * The class is thread-safe. An analysis runs one read of a file at a time.
 */
internal class RememberedReads<T : Any> {

    private val last = ConcurrentHashMap<Path, RememberedRead<T>>()

    /**
     * The result of reading [snapshot], the text of the file at [path], against [input]. If the last read of the file
     * had the same text and input, the remembered result is returned. Otherwise it returns what [read] returns and
     * remembers that in its place.
     */
    fun read(path: Path, snapshot: TextSnapshot, input: Any?, read: (TextSnapshot) -> ReadResult<T>): ReadResult<T> {
        val remembered = last[path]
        if (remembered != null && remembered.input === input && remembered.text == snapshot.text) return remembered.result
        return read(snapshot).also { last[path] = RememberedRead(snapshot.text, input, it) }
    }

    /** Forgets the reads of every file but those at [paths]. */
    fun retainOnly(paths: Set<Path>) {
        last.keys.retainAll(paths)
    }
}

/**
 * One remembered read, the [result] of reading [text] against [input].
 *
 * @property text the text that was read.
 * @property input what the text was read against, or null if nothing.
 * @property result what the reader returned.
 */
private class RememberedRead<T : Any>(val text: String, val input: Any?, val result: ReadResult<T>)

/**
 * What the analyses of one workspace remember of their reads. There is one [RememberedReads] per kind of model: the
 * metamodels including the events, the models and the state machines.
 */
internal class ReadMemory {
    /** The reads of metamodels and events. */
    val metamodels: RememberedReads<Metamodel> = RememberedReads()

    /** The reads of models. */
    val models: RememberedReads<Model> = RememberedReads()

    /** The reads of state machines. */
    val stateMachines: RememberedReads<StateMachine> = RememberedReads()

    /** Forgets the reads of every file but those at [paths], the files of the contexts of the latest analysis. */
    fun retainOnly(paths: Set<Path>) {
        metamodels.retainOnly(paths)
        models.retainOnly(paths)
        stateMachines.retainOnly(paths)
    }
}
