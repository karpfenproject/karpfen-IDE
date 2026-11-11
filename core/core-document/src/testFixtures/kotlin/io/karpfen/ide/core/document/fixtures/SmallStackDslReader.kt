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

import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.ClassType
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.states.StateMachine
import io.karpfen.ide.core.document.dsl.DslReader
import io.karpfen.ide.core.document.dsl.ReadResult
import io.karpfen.ide.core.text.TextSnapshot

/**
 * A [DslReader] that hands every read to [delegate] on a thread of its own with the stack [DeepNesting.SMALL_STACK].
 * The delegate is the real reader of dsl-tools by default. A text may nest a few hundred levels deep, as
 * [DeepNesting.guardedKstates] does, and still stay within the nesting limit of the IDE's parsers. On such a text a
 * dsl-tools reader then runs out of stack. It reports a `reader.failure` and logs the failure as it does in use. The
 * IDE's own syntax analysis of the same text follows it on a [DeepNesting.LARGE_STACK]. This is how end-to-end tests
 * make a real dsl-tools reader fail.
 */
public class SmallStackDslReader(private val delegate: DslReader = DslReader()) : DslReader {

    override fun kmeta(source: TextSnapshot, baseTypes: List<ClassType>): ReadResult<Metamodel> =
        DeepNesting.onStack(DeepNesting.SMALL_STACK) { delegate.kmeta(source, baseTypes) }

    override fun kmodel(source: TextSnapshot, metamodel: Metamodel): ReadResult<Model> =
        DeepNesting.onStack(DeepNesting.SMALL_STACK) { delegate.kmodel(source, metamodel) }

    override fun kstates(source: TextSnapshot): ReadResult<StateMachine> =
        DeepNesting.onStack(DeepNesting.SMALL_STACK) { delegate.kstates(source) }
}
