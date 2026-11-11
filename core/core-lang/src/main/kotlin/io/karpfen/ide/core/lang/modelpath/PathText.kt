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
package io.karpfen.ide.core.lang.modelpath

import io.karpfen.ide.core.document.syntax.KarpfenSyntax
import io.karpfen.ide.core.text.TextRange

/** The vocabulary of KStates model paths. */
internal object PathSyntax {
    /** The root segment that names the object of the state machine. */
    const val SELF: String = "self"

    /** The root segment that names the event in scope. It is also the type name of an undetermined event. */
    const val EVENT: String = "event"

    /** The segment separator, which is [KarpfenSyntax.MODEL_PATH_SEPARATOR]. */
    const val SEPARATOR: String = KarpfenSyntax.MODEL_PATH_SEPARATOR
}

/** A segment of a model path as written. [name] has its blanks removed, and [range] has its blanks trimmed. */
internal data class PathSegment(val name: String, val range: TextRange)

/**
 * The segments of the model path written in [range] of [text], such as `a -> b->c`, split at `->`. There is one entry
 * per segment, also for empty ones, so `a->` yields `a` and an empty segment at the end. The function has no side
 * effects, and ranges are clamped to [text].
 */
internal fun pathSegments(text: String, range: TextRange): List<PathSegment> {
    val end = minOf(range.end, text.length)
    val start = minOf(range.start, end)
    val segments = ArrayList<PathSegment>()
    var segmentStart = start
    var i = start
    while (i <= end) {
        val atSeparator = KarpfenSyntax.isPathSeparatorAt(text, i, end)
        if (i == end || atSeparator) {
            segments += segmentOf(text, segmentStart, i)
            if (i == end) break
            segmentStart = i + SEPARATOR_LENGTH
            i += SEPARATOR_LENGTH
        } else {
            i++
        }
    }
    return segments
}

/** The index of the segment whose range contains [offset], with both bounds included, or -1. */
internal fun List<PathSegment>.indexAt(offset: Int): Int = indexOfFirst { it.range.containsInclusive(offset) }

/** The path of the first [count] segments joined by `->`. A resolver needs it to reach the segment at `count - 1`. */
internal fun List<PathSegment>.pathOf(count: Int): String = take(count).joinToString(PathSyntax.SEPARATOR) { it.name }

/** The segment written between [from] and [to] of [text], blanks trimmed from its range and removed from its name. */
private fun segmentOf(text: String, from: Int, to: Int): PathSegment {
    var start = from
    var end = to
    while (start < end && KarpfenSyntax.isBlank(text[start])) start++
    while (end > start && KarpfenSyntax.isBlank(text[end - 1])) end--
    return PathSegment(KarpfenSyntax.normalizeModelPath(text.substring(start, end)), TextRange(start, end))
}

/** The length of the segment separator `->`. */
private val SEPARATOR_LENGTH = PathSyntax.SEPARATOR.length

/**
 * The segments of a model path reference at [range]. [path] is spelled as in `SymbolReference.name`, without blanks.
 * The segments are read from [text] when it is the text the range belongs to. Otherwise they are derived from [path]
 * when the written path has no blanks. If neither works, every segment gets the whole [range].
 */
internal fun pathSegmentsOf(path: String, range: TextRange, text: String?): List<PathSegment> = when {
    text != null && range.end <= text.length -> pathSegments(text, range)
    range.length == path.length ->
        pathSegments(path, TextRange(0, path.length)).map { PathSegment(it.name, it.range.shiftedBy(range.start)) }
    else -> path.split(PathSyntax.SEPARATOR).map { PathSegment(it, range) }
}
