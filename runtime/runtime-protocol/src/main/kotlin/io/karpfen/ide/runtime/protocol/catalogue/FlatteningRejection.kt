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
 * A state machine of an environment that does not flatten, as a line of [FlatteningRejection] names it. Values of this
 * type are immutable.
 */
public sealed interface UnflattenedMachine {
    /** The state machine as the start of its line names it, such as `State machine for 'lamp'`. */
    public val words: String

    /**
     * A state machine uploaded with `attachedTo`.
     *
     * @property attachedTo the object key it is attached to.
     */
    public data class Attached(public val attachedTo: String) : UnflattenedMachine {
        init {
            require('\n' !in attachedTo) { "the key a state machine is attached to stays on one line: '$attachedTo'" }
        }

        override val words: String get() = "State machine for '$attachedTo'"
    }

    /**
     * An attachable state machine, which the environment holds under its name.
     *
     * @property name its name.
     */
    public data class Attachable(public val name: String) : UnflattenedMachine {
        init {
            require('\n' !in name) { "the name of an attachable state machine stays on one line: '$name'" }
        }

        override val words: String get() = "Attachable state machine '$name'"
    }
}

/**
 * One problem of a [FlatteningRejection]: a state machine of the environment and why it does not flatten. Immutable.
 *
 * @property machine the state machine the problem belongs to.
 * @property message the problem in the words of dsl-tools' flattener, on one line. It names the container state, the
 *   file it stands in and the fragment, the macro or the state concerned.
 */
public data class FlatteningProblemLine(public val machine: UnflattenedMachine, public val message: String) {
    init {
        require(message.isNotEmpty() && '\n' !in message) { "the message of a problem is one line that is not empty: '$message'" }
    }

    /** The line as the runtime writes it: `<machine>: <message>`. */
    public fun render(): String = "${machine.words}: $message"
}

/**
 * The body with which the runtime refuses to activate an environment whose state machines do not flatten, parsed. The
 * runtime flattens every attached and every attachable state machine with the fragments the environment holds before
 * it activates anything. When one of them does not flatten, for example because a container state uses a fragment that
 * was never uploaded, it answers `POST /runEnvironment` with the status 409 and this body, and the environment stays
 * configuring.
 *
 * The body is plain text, and its lines are separated by `\n` without a line feed at the end. The first line is
 * `Environment with key <env> has state machines that cannot be flattened:`. Then follows one line per problem, as
 * [FlatteningProblemLine.render] writes it: the attached state machines first in the order of their upload, then the
 * attachable ones. Immutable.
 *
 * @property environment the key of the environment, as the first line names it.
 * @property problems the problems, at least one, in the order of the body.
 */
public data class FlatteningRejection(public val environment: String, public val problems: List<FlatteningProblemLine>) {
    init {
        require(problems.isNotEmpty()) { "a refused activation has at least one problem" }
        require(environment.none { it.isWhitespace() }) { "an environment key has no whitespace: '$environment'" }
    }

    /** The body exactly as the runtime writes it. */
    public fun render(): String =
        (listOf("Environment with key $environment $HEADLINE_END") + problems.map(FlatteningProblemLine::render)).joinToString(LINE_SEPARATOR)

    public companion object {
        /** The separator of the lines of the body. */
        private const val LINE_SEPARATOR: String = "\n"

        /** The end of the first line, after the environment key. */
        private const val HEADLINE_END: String = "has state machines that cannot be flattened:"

        /** The first line, with the environment key as its group. */
        private val HEADLINE = Regex("""Environment with key (\S*) ${Regex.escape(HEADLINE_END)}""")

        /** The line of a problem of an attached state machine, with the key and the message as its groups. */
        private val ATTACHED_LINE = Regex("""State machine for '(.*?)': (.+)""")

        /** The line of a problem of an attachable state machine, with the name and the message as its groups. */
        private val ATTACHABLE_LINE = Regex("""Attachable state machine '(.*?)': (.+)""")

        /**
         * Parses [body] if it is exactly the body of a refused activation, and returns null for any other text. The
         * first line must be the headline, at least one line must follow, and every following line must be the line of
         * one problem. A trailing line feed or an empty line makes the whole body unknown. The name in a line ends at
         * the first `': `, since the runtime never puts that into an object key or a state machine name it writes.
         */
        public fun parse(body: String): FlatteningRejection? {
            val lines = body.split(LINE_SEPARATOR)
            val headline = HEADLINE.matchEntire(lines.first()) ?: return null
            if (lines.size < 2) return null
            val problems = lines.drop(1).map { line -> problemOf(line) ?: return null }
            return FlatteningRejection(headline.groupValues[1], problems)
        }

        /** The problem that [line] is, or null if it is not the line of a problem. */
        private fun problemOf(line: String): FlatteningProblemLine? {
            ATTACHED_LINE.matchEntire(line)?.let { match ->
                return FlatteningProblemLine(UnflattenedMachine.Attached(match.groupValues[1]), match.groupValues[2])
            }
            ATTACHABLE_LINE.matchEntire(line)?.let { match ->
                return FlatteningProblemLine(UnflattenedMachine.Attachable(match.groupValues[1]), match.groupValues[2])
            }
            return null
        }
    }
}
