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
package io.karpfen.ide.core.document.parse

import io.karpfen.ide.core.text.TextRange

/**
 * How deep the texts of the modeling languages may nest. Their parsers follow at most [MAX_DEPTH] levels of braces.
 *
 * The parsers of these languages descend once per level. This holds for the IDE's own parsers and for those inside the
 * readers of dsl-tools. How many levels fit on the stack of a thread changes from run to run with the state of the JIT
 * compiler. So a text that nests deeper than [MAX_DEPTH] is not parsed at all. It gets one `nesting-too-deep` error at
 * the brace that opens the first level too many, the same on every run. It is not syntax-clean, so no reader of
 * dsl-tools reads it either.
 *
 * Both kinds of parser follow more than twice as many levels on the default stack of a thread, in every state of the
 * JIT compiler. That stack is one megabyte on most platforms. A text within the limit therefore always parses and
 * reads, and the same files are always reported the same way. Hand-written models nest a few dozen levels at most.
 *
 * Every opening brace counts, including those of `EVAL` code, and a closing brace leaves its level again. Braces
 * inside string literals and karpfen comments do not count. Environment descriptors are limited by the JSON parser
 * instead.
 */
public object NestingLimit {
    /** The most levels of braces that a text of a modeling language may nest. */
    public const val MAX_DEPTH: Int = 300
}

/**
 * Thrown by a [SyntaxAnalyzer] instead of parsing a text that nests deeper than [NestingLimit.MAX_DEPTH].
 * [ComposedDocumentParser] reports it as `nesting-too-deep` at [range], the brace that opens the first level too many.
 * It is the expected answer to such a text and not a failure, so it carries no stack trace.
 *
 * @property range the opening brace of the first level beyond the limit.
 */
internal class NestingLimitExceeded(val range: TextRange) :
    RuntimeException("the text nests deeper than ${NestingLimit.MAX_DEPTH} levels", null, false, false)
