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
package io.karpfen.ide.core.document.frame

import io.karpfen.ide.core.document.language.LanguageSupport
import io.karpfen.ide.core.document.parse.ParseOutcome
import io.karpfen.ide.core.document.parse.SemanticMode
import io.karpfen.ide.core.document.registry.fragmentSupport
import io.karpfen.ide.core.document.syntax.TokenizeResult
import io.karpfen.ide.core.text.TextFrame
import io.karpfen.ide.core.text.TextFraming
import io.karpfen.ide.core.text.TextLineage
import io.karpfen.ide.core.text.TextSnapshot
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * A document that holds a piece of the file at [path], such as the code of one block that a diagram shows in a code
 * cell, seen in its place in the whole file. [framing] puts each text of the piece into the whole text of the file.
 * The language of the file then works on that whole text, and what it finds is mapped back onto the piece. So the
 * piece is checked and understood as if it stood in the file, while the file itself stays as it is.
 *
 * A session of the piece uses [support]. It colours the piece with the tokenizer of the file's language and parses the
 * whole text with the parser of [whole], as [FramedDocumentParser] describes. The language services see the whole text
 * through [view]. A piece that [framing] cannot place falls back to [fragmentSupport]: it is coloured but not checked,
 * and [view] gives null.
 *
 * Every whole text has the version of its piece and one lineage of this document. The document remembers the latest
 * parse of a whole text and the latest one without syntax errors, so the language services can follow the staleness
 * rule of their documents: the tokens are always those of the current whole text, and the parse may be older. It is
 * thread-safe: the parser runs on the parse dispatcher of the session and the services on theirs.
 *
 * @property path the file the piece belongs to. The language services find its modeling context by this path.
 * @property whole the language support of the file.
 */
public class FramedDocument(
    public val path: Path,
    public val whole: LanguageSupport,
    private val framing: TextFraming,
) {

    /** The lineage of every whole text of this document. */
    private val lineage = TextLineage()

    /** The latest parse of a whole text. */
    private val latest = AtomicReference<ParseOutcome?>(null)

    /** The latest parse of a whole text without syntax errors. */
    private val latestGood = AtomicReference<ParseOutcome?>(null)

    /** The support of a piece that cannot be placed. */
    private val fallback: LanguageSupport = fragmentSupport(whole)

    /** The language support of a session that edits the piece. */
    public val support: LanguageSupport = LanguageSupport(
        whole.language,
        whole.tokenizer,
        FramedDocumentParser(this, fallback.parser),
        whole.lineComment,
    )

    /**
     * The whole text for [piece], a version of the piece's text, or null if [framing] cannot place it. A failure of
     * [framing] is logged and counts as a piece that cannot be placed, so it never reaches the parser or the services.
     */
    public fun frame(piece: TextSnapshot): FramedText? {
        val frame = try {
            framing.frame(piece.text)
        } catch (e: Exception) {
            logger.error("Framing a piece of {} (version {}) failed", path, piece.version, e)
            null
        }
        return frame?.let { FramedText(it, TextSnapshot(it.whole, piece.version, lineage)) }
    }

    /**
     * The whole text for [piece] as the language services see it: the tokens of exactly that whole text, the latest
     * parse of a whole text of this document and the latest one without syntax errors. Both parses may be older than
     * [piece]. It is null if [framing] cannot place [piece].
     */
    public fun view(piece: TextSnapshot): WholeView? {
        val framed = frame(piece) ?: return null
        return WholeView(framed.frame, framed.snapshot, whole.tokenizer.tokenize(framed.snapshot), latest.get(), latestGood.get())
    }

    /** Parses the whole text of [framed] with the parser of [whole] and remembers the outcome. */
    internal fun parse(framed: FramedText): ParseOutcome {
        val outcome = whole.parser.parse(framed.snapshot, SemanticMode.SYNTAX_ONLY)
        keepNewer(latest, outcome)
        if (outcome.isSyntaxClean) keepNewer(latestGood, outcome)
        return outcome
    }

    /** Makes [outcome] the value of [reference] unless the value is of a newer version. */
    private fun keepNewer(reference: AtomicReference<ParseOutcome?>, outcome: ParseOutcome) {
        reference.updateAndGet { known -> if (known != null && known.version > outcome.version) known else outcome }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FramedDocument::class.java)
    }
}

/**
 * A piece put in its place in the whole text.
 *
 * @property frame the frame, with the mapping of positions between the piece and the whole text.
 * @property snapshot the whole text, at the version of the piece.
 */
public class FramedText(public val frame: TextFrame, public val snapshot: TextSnapshot)

/**
 * The whole text of a piece as the language services see it.
 *
 * @property frame the frame of the piece, with the mapping of positions.
 * @property snapshot the whole text, at the version of the piece.
 * @property tokens the tokens of [snapshot].
 * @property parse the latest parse of a whole text of the document, possibly of an older version, or null.
 * @property lastGoodParse the latest parse of a whole text without syntax errors, possibly older, or null.
 */
public class WholeView(
    public val frame: TextFrame,
    public val snapshot: TextSnapshot,
    public val tokens: TokenizeResult,
    public val parse: ParseOutcome?,
    public val lastGoodParse: ParseOutcome?,
)
