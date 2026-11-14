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
package io.karpfen.ide.runtime.protocol.catalogue

/**
 * What an upload that the runtime refused for its content was, as the first line of [UploadRejection] names it.
 * Values of this type are immutable.
 */
public sealed interface RejectedUpload {
    /** The upload as the first line of the body names it, such as `model` or `state machine for 'lamp'`. */
    public val words: String

    /** The domain metamodel, sent to `PUT /setMetamodel`. */
    public data object Metamodel : RejectedUpload {
        override val words: String get() = "metamodel"
    }

    /** The model, sent to `PUT /setModel`. */
    public data object Model : RejectedUpload {
        override val words: String get() = "model"
    }

    /** The event definitions, sent to `PUT /setEventDefinitions`. */
    public data object EventDefinitions : RejectedUpload {
        override val words: String get() = "event definitions"
    }

    /**
     * A state machine, sent to `PUT /setStateMachine`.
     *
     * @property attachedTo the object key the machine was to be attached to, as the request named it.
     */
    public data class StateMachine(public val attachedTo: String) : RejectedUpload {
        init {
            require('\n' !in attachedTo) { "the key a state machine is attached to stays on one line: '$attachedTo'" }
        }

        override val words: String get() = "state machine for '$attachedTo'"
    }

    /**
     * An attachable state machine, sent to `PUT /setStateMachine` without `attachedTo`. The first line does not name
     * it, because the runtime could not read its name from a text with errors.
     */
    public data object AttachableStateMachine : RejectedUpload {
        override val words: String get() = "attachable state machine"
    }
}

/**
 * One error of an upload the runtime refused, at the place in the uploaded text where the runtime's reader found it.
 * Immutable.
 *
 * @property line the line of the place, counted from 1.
 * @property column the column of the place, counted from 1 in UTF-16 characters, like the indices of a `String`.
 * @property message what is wrong, in the reader's words, on one line.
 * @property code the stable id of the reader's diagnostic, such as `kmodel.unknown-property`.
 */
public data class UploadError(
    public val line: Int,
    public val column: Int,
    public val message: String,
    public val code: String,
) {
    init {
        require(line >= 1) { "the line of an error counts from 1, not $line" }
        require(column >= 1) { "the column of an error counts from 1, not $column" }
        require(message.isNotEmpty() && '\n' !in message) { "the message of an error is one line that is not empty: '$message'" }
        require(CODE.matches(code)) { "the code of an error has no blank and no parenthesis: '$code'" }
    }

    /** The error as the runtime writes it on its line: `<line>:<column>: <message> (<code>)`. */
    public fun render(): String = "$line:$column: $message ($code)"

    private companion object {
        /** The form of a code: characters that are neither blank nor a parenthesis. */
        val CODE: Regex = Regex("""[^\s()]+""")
    }
}

/**
 * The body with which the runtime refuses an upload whose text has at least one error, parsed. The runtime reads every
 * upload with the readers of karpfen-dsl-tools and answers such an upload with the status 400 and this body.
 *
 * The body is plain text, and its lines are separated by `\n` without a line feed at the end. The first line is
 * `Upload of the <upload> rejected: <n> errors`, written `1 error` for a single one, where `<upload>` is the
 * [RejectedUpload.words] of the upload. Then follows one line per error in the order of the text, as
 * [UploadError.render] writes it. The runtime writes a line break inside a message as a space. Warnings never reject an
 * upload, so the body lists errors only. Immutable.
 *
 * @property upload what was uploaded.
 * @property errors the errors in the order of the text. There is at least one.
 */
public data class UploadRejection(public val upload: RejectedUpload, public val errors: List<UploadError>) {
    init {
        require(errors.isNotEmpty()) { "a refused upload has at least one error" }
    }

    /** The body exactly as the runtime writes it. */
    public fun render(): String =
        (listOf("Upload of the ${upload.words} rejected: ${countOf(errors.size)}") + errors.map(UploadError::render))
            .joinToString(LINE_SEPARATOR)

    public companion object {
        /** The separator of the lines of the body. */
        private const val LINE_SEPARATOR: String = "\n"

        /** The first line, with the upload as its first group and the number of errors and its noun after it. */
        private val HEADLINE = Regex("""Upload of the (metamodel|model|event definitions|attachable state machine|state machine for '([^\n]*)') rejected: ([1-9]\d{0,8}) (errors?)""")

        /** A line of one error, with the line, the column, the message and the code as its groups. */
        private val ERROR_LINE = Regex("""([1-9]\d{0,8}):([1-9]\d{0,8}): ([^\n]+) \(([^\s()]+)\)""")

        /**
         * Parses [body] if it is exactly the body of a refused upload, and returns null for any other text. The parse
         * is strict. The first line must name one of the five uploads, the number of errors must be the number of the
         * lines that follow and agree with its noun, and every following line must be the line of one error. A
         * trailing line feed, an empty line, a number with a leading zero or a line of any other form makes the whole
         * body unknown.
         */
        public fun parse(body: String): UploadRejection? {
            val lines = body.split(LINE_SEPARATOR)
            val headline = HEADLINE.matchEntire(lines.first()) ?: return null
            val upload = uploadOf(headline.groupValues[1], headline.groupValues[2])
            val count = headline.groupValues[3].toInt()
            if (headline.groupValues[4] != nounOf(count) || lines.size - 1 != count) return null
            val errors = lines.drop(1).map { line -> errorOf(line) ?: return null }
            return UploadRejection(upload, errors)
        }

        /** The upload that the first line names with [words], with [attachedTo] for an attached state machine. */
        private fun uploadOf(words: String, attachedTo: String): RejectedUpload =
            when (words) {
                RejectedUpload.Metamodel.words -> RejectedUpload.Metamodel
                RejectedUpload.Model.words -> RejectedUpload.Model
                RejectedUpload.EventDefinitions.words -> RejectedUpload.EventDefinitions
                RejectedUpload.AttachableStateMachine.words -> RejectedUpload.AttachableStateMachine
                else -> RejectedUpload.StateMachine(attachedTo)
            }

        /** The error that [line] is, or null if it is not the line of an error. */
        private fun errorOf(line: String): UploadError? {
            val match = ERROR_LINE.matchEntire(line) ?: return null
            val (number, column, message, code) = match.destructured
            return UploadError(number.toInt(), column.toInt(), message, code)
        }

        /** The number [count] with its noun, as the first line writes it. */
        private fun countOf(count: Int): String = "$count ${nounOf(count)}"

        /** The noun of [count] errors: `error` for one, `errors` for any other number. */
        private fun nounOf(count: Int): String = if (count == 1) "error" else "errors"
    }
}
