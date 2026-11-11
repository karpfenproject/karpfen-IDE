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
package io.karpfen.ide.core.text

/**
 * A zero-based line and column position in a document. [column] counts UTF-16 code units from the start of the line.
 * A tab character counts as one column here, because display columns are a UI concern. The UI shows both values
 * one-based.
 */
public data class TextPosition(public val line: Int, public val column: Int) : Comparable<TextPosition> {

    init {
        require(line >= 0) { "line must be >= 0, was $line" }
        require(column >= 0) { "column must be >= 0, was $column" }
    }

    /** Orders by [line], then by [column]. */
    override fun compareTo(other: TextPosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)

    override fun toString(): String = "$line:$column"

    public companion object {
        /** The start of the document. */
        public val ORIGIN: TextPosition = TextPosition(0, 0)
    }
}
