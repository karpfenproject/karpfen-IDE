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
package io.karpfen.ide.notation.diagram.structure

/**
 * Hands out ids that differ although the names they are made of may repeat. The first use of a name gets the name
 * itself, the second one gets `name#2`, and so on. So the id of something that is declared once is its plain name,
 * and a text that declares a name twice still gives a diagram.
 */
internal class UniqueIds {

    private val counts = HashMap<String, Int>()

    /** The id for the next use of [name]. */
    fun next(name: String): String {
        val ordinal = counts.merge(name, 1, Int::plus)
        return if (ordinal == 1) name else "$name#$ordinal"
    }
}
