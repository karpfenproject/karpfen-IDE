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
 * The identity of one line of text versions: every [TextSnapshot] of one [EditableText] carries the same lineage, and
 * a snapshot created on its own (a file loaded from disk, a test value) has a lineage of its own.
 *
 * Versions count per lineage (each [EditableText] starts at 0, a document closed and reopened starts again at 0), so a
 * version alone does not say which text it belongs to. [EditableText.mapRange] takes the range's lineage and returns
 * null for a range of another lineage, which the caller then drops or clamps instead of mapping it through an
 * unrelated edit history.
 *
 * Equality is identity; no global counter or other shared state is involved.
 */
public class TextLineage {
    override fun toString(): String = "TextLineage@${Integer.toHexString(System.identityHashCode(this))}"
}
