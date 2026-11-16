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
package io.karpfen.ide.core.workspace.diagnostics

/**
 * The stable codes, used as `Diagnostic.code`, of the findings that only core-workspace produces. All of them have the
 * source `DiagnosticSource.IDE`. They cover file loading in `SourceLoader`, files and directories that cannot be read,
 * environment descriptors in `EnvironmentDescriptorReader` and modeling contexts in `ContextResolver`. The codes of the
 * lexer, the parser, EVAL, dsl-tools and internal errors live in `DiagnosticCodes` of core-document. A new workspace
 * finding adds its code here, so every code is declared exactly once. The package is a leaf, so every producer may
 * depend on it without package cycles.
 */
public object WorkspaceDiagnosticCodes {
    /** The file mixes line separators. It is an INFO, reported once when the file is loaded. */
    public const val MIXED_LINE_SEPARATORS: String = "mixed-line-separators"

    /**
     * A file or a directory of the workspace cannot be read, so it, or the files in it, were not analyzed. It is an
     * ERROR at the start of the file or directory itself. Passing silently would let a validation of the project accept
     * a tree it never saw.
     */
    public const val UNREADABLE: String = "unreadable"

    /** The file is not valid UTF-8 and was decoded with replacement characters. It is a WARNING. */
    public const val MALFORMED_ENCODING: String = "malformed-encoding"

    /**
     * An environment descriptor misses a required key, repeats a key, has a value of the wrong kind or names a file in
     * the wrong language. It is an ERROR. The JSON syntax errors of a descriptor are the lexer and syntax errors of the
     * environment language and have their own codes.
     */
    public const val DESCRIPTOR_INVALID: String = "descriptor-invalid"

    /** An environment descriptor contains an unknown key. It is a WARNING. */
    public const val DESCRIPTOR_UNKNOWN_KEY: String = "descriptor-unknown-key"

    /** An environment descriptor names a file that does not exist. It is an ERROR. */
    public const val DESCRIPTOR_MISSING_FILE: String = "descriptor-missing-file"

    /**
     * A state machine named in an environment descriptor has the wrong form for its entry. An entry with `attachedTo`
     * needs the attached form and an entry without it the attachable form, which makes a definition. That is an ERROR.
     * A definition listed twice is a WARNING with the same code.
     */
    public const val DESCRIPTOR_STATE_MACHINE_FORM: String = "descriptor-state-machine-form"

    /**
     * An implicit modeling context contains several domain metamodels. Each of them gets an INFO, and the
     * alphabetically first one is used.
     */
    public const val CONTEXT_MULTIPLE_METAMODELS: String = "context-multiple-metamodels"

    /** A modeling context has models or state machines but no metamodel to analyze them with. It is an INFO. */
    public const val CONTEXT_NO_METAMODEL: String = "context-no-metamodel"
}
