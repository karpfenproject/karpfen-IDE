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
 * The exact texts with which the runtime rejects a call (answered with 400 or 409, or with any status for
 * [UNEXPECTED_ERROR]), as templates with placeholders in braces: `{env}` an environment key, `{client}` a client id,
 * `{name}` a parameter name, `{element}` a model element, `{state}` a state name, `{keys}` a comma-separated list of
 * object keys, `{id}` an object id and `{ms}` a number of milliseconds.
 *
 * A body is one of these texts only if it is the whole text ([match]): `{env}` and `{client}` match a run of
 * non-whitespace characters, possibly empty (the runtime renders a present but empty parameter as nothing), `{ms}`
 * matches digits and every other placeholder any text on one line. An environment key never starts with a single quote
 * (`env-<millis>`), so `{env}` matches no text that does: that keeps [NO_EXECUTION_THREAD] apart from [NOT_ACTIVATED],
 * whose template quotes the key, and makes the templates disjoint, so a body matches at most one of them.
 *
 * @property template the text with its placeholders.
 * @property kind the rejection kind the text stands for.
 */
public enum class RuntimeText(public val template: String, public val kind: RejectionKind) {
    /** A query parameter is missing (400). */
    MISSING_PARAMETER("Missing required parameter: {name}", RejectionKind.MISSING_PARAMETER),

    /** The parameter `milliseconds` or `ttlMs` is missing or not a number (400). */
    INVALID_PARAMETER("Missing or invalid required parameter: {name}", RejectionKind.INVALID_PARAMETER),

    /** The environment does not exist (400). */
    UNKNOWN_ENVIRONMENT("Environment with key {env} does not exist", RejectionKind.UNKNOWN_ENVIRONMENT),

    /** The environment is active: setters, observers, listeners and a second activation are refused (409). */
    ALREADY_ACTIVE(
        "Environment with key {env} is already active and cannot be modified in the intended way.",
        RejectionKind.ALREADY_ACTIVE,
    ),

    /** `setModel` before `setMetamodel` (409). */
    METAMODEL_REQUIRED("Metamodel must be set before receiving a model for environment {env}", RejectionKind.METAMODEL_REQUIRED),

    /** `runEnvironment` without a metamodel or a model (409). */
    MODELS_REQUIRED(
        "Environment with key {env} must have both a metamodel and a model before it can be run",
        RejectionKind.MODELS_REQUIRED,
    ),

    /** `runEnvironment` with state machines attached to keys the model lacks, comma-separated (409). */
    UNKNOWN_ATTACHMENT(
        "Environment with key {env} has state machines attached to objects missing from its model: {keys}",
        RejectionKind.UNKNOWN_ATTACHMENT,
    ),

    /** `startEnvironment` or `stopEnvironment` on a configuring environment, the key in single quotes (409). */
    NOT_ACTIVATED("Environment with key '{env}' has no execution thread", RejectionKind.NOT_ACTIVATED),

    /** `setActiveState` without an execution thread, the key without quotes (409); unreachable since activation is atomic. */
    NO_EXECUTION_THREAD("Environment with key {env} has no execution thread", RejectionKind.NO_EXECUTION_THREAD),

    /** `setActiveState` on an environment that is not active (409). */
    NOT_ACTIVE("Environment with key {env} is not active", RejectionKind.NOT_ACTIVE),

    /** `setActiveState` for a model element without a state machine (400). */
    UNKNOWN_STATE_MACHINE(
        "No state machine for model element '{element}' in environment {env}",
        RejectionKind.UNKNOWN_STATE_MACHINE,
    ),

    /** `setActiveState` with a state the machine does not have (400). */
    UNKNOWN_STATE("Unknown state '{state}' for model element '{element}'", RejectionKind.UNKNOWN_STATE),

    /** `observatory/statemachine` for a model element without an uploaded text (400). */
    NO_STATE_MACHINE_SOURCE(
        "No state machine source found for model element '{element}' in environment {env}",
        RejectionKind.NO_STATE_MACHINE_SOURCE,
    ),

    /** `observatory/model` of a configuring environment without a model (409). */
    NO_MODEL("Environment with key {env} has no model", RejectionKind.NO_MODEL),

    /** `observatory/model` of a running environment without a tick boundary within the runtime's wait (409). */
    TICK_BOUNDARY_TIMEOUT(
        "Environment with key {env} did not reach a tick boundary within {ms} ms",
        RejectionKind.TICK_BOUNDARY_TIMEOUT,
    ),

    /** `observatory/model` of a model with an object reachable from itself (409). */
    REFERENCE_LOOP("Reference loop through object '{id}' cannot be written as JSON", RejectionKind.REFERENCE_LOOP),

    /** `observatory/subscribeModel` by a client without an attached WebSocket session (409). */
    NO_SESSION("Client {client} has no open WebSocket session for environment {env}", RejectionKind.NO_SESSION),

    /** An exception without a message, answered with 400, 409 or 500. */
    UNEXPECTED_ERROR("Unexpected error", RejectionKind.UNEXPECTED_ERROR),
    ;

    /** The template as a pattern of whole bodies. */
    private val pattern: TextPattern = TextPattern(template)

    /**
     * The text with every placeholder replaced by its value in [values], such as the text the runtime answers.
     *
     * @throws IllegalArgumentException if [values] lacks a placeholder of the template.
     */
    public fun render(values: Map<String, String>): String = pattern.render(values)

    /**
     * The placeholder values if [body] is exactly this text, else null; an empty map for a text without placeholders.
     */
    public fun match(body: String): Map<String, String>? = pattern.match(body)
}

/**
 * A template of [RuntimeText] compiled into a regular expression of whole bodies, with one group per placeholder.
 *
 * @property template the text with its placeholders in braces.
 */
internal class TextPattern(private val template: String) {

    /** The placeholders of the template, in the order of their groups. */
    private val placeholders: List<String> = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()

    /** The template with every literal part quoted and every placeholder replaced by its group. */
    private val regex: Regex = Regex(
        PLACEHOLDER.split(template).map(Regex::escape)
            .zip(placeholders.map { "(${groupOf(it)})" } + "")
            .joinToString("") { (literal, group) -> literal + group },
    )

    /** See [RuntimeText.render]. */
    fun render(values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { placeholder ->
            val name = placeholder.groupValues[1]
            requireNotNull(values[name]) { "no value for the placeholder {$name} of '$template'" }
        }

    /** See [RuntimeText.match]. */
    fun match(body: String): Map<String, String>? {
        val result = regex.matchEntire(body) ?: return null
        return placeholders.withIndex().associate { (index, name) -> name to result.groupValues[index + 1] }
    }

    private companion object {
        /** A placeholder: a name in braces. */
        val PLACEHOLDER = Regex("""\{(\w+)}""")

        /** A run of non-whitespace characters that does not start with a single quote, possibly empty. */
        const val ENVIRONMENT = """(?:[^\s']\S*)?"""

        /** A run of non-whitespace characters, possibly empty. */
        const val CLIENT = """\S*"""

        /** Digits. */
        const val MILLISECONDS = """\d+"""

        /** Any text on one line. */
        const val ANY = """.*"""

        /** The pattern of the placeholder [name]. */
        fun groupOf(name: String): String = when (name) {
            "env" -> ENVIRONMENT
            "client" -> CLIENT
            "ms" -> MILLISECONDS
            else -> ANY
        }
    }
}
