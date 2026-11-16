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
package io.karpfen.ide.core.document.dsl

import io.karpfen.dsl.diagnostics.DiagnosticCode
import io.karpfen.dsl.instance.Model
import io.karpfen.dsl.meta.Metamodel
import io.karpfen.dsl.reader.ParseResult
import io.karpfen.dsl.source.SourceMap
import io.karpfen.dsl.source.SourcePosition
import io.karpfen.dsl.source.SourceRange
import io.karpfen.dsl.states.StateMachine
import io.karpfen.dsl.verification.ProjectInput
import io.karpfen.dsl.verification.ProjectVerifier
import io.karpfen.dsl.verification.StateMachineInput
import io.karpfen.ide.core.document.Diagnostic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import io.karpfen.dsl.diagnostics.Diagnostic as DslDiagnostic
import io.karpfen.dsl.diagnostics.Severity as DslSeverity

/**
 * Adapter over the project verifier of dsl-tools, `ProjectVerifier` in the package `io.karpfen.dsl.verification`. It
 * checks the state machines of a project against the metamodels and the model they belong to, and the `behaves after`
 * clauses of the metamodels against the attachable state machines of the project. These checks span several files,
 * such as whether a path of an action rule leads anywhere in the metamodel, whether a state can ever be active or
 * whether a name after `ATTACH` belongs to an attachable state machine, and their codes start with `project.`. The
 * readers behind [DslReader] never run them. The adapter is the only way IDE code reaches the verifier, because the
 * architecture tests allow dsl-tools beyond its semantic model only in this package.
 *
 * The verifier works on what the readers read. So it takes the [ReadResult]s of a [DslReader], which keep the reader's
 * own result with its source map for this purpose. A read without it, such as one a test builds by hand, cannot be
 * verified: a state machine without it gets no findings, and if the metamodel, the events or the model lacks it,
 * nothing gets any.
 *
 * Every finding becomes one [Diagnostic] with source `DSL_TOOLS` in the coordinates of the text it concerns, with the
 * stable id of its dsl-tools code, such as `project.unknown-path`, as its code. Most concern the text of a state
 * machine, those of the `behaves after` clauses the text of the domain metamodel or of the event metamodel. The
 * verifier reports nothing it cannot be sure of. It leaves out a state machine with a syntax error, and a check that
 * needs a text without errors stays silent while that text has errors. A name that no attachable state machine has is
 * reported only if every state machine of the project could be read, so [VerificationInput.unreadMachines] tells the
 * verifier how many state machine texts of the project have no read.
 *
 * The adapter never throws for any input, except for fatal VM errors. Should the verifier fail nevertheless, the
 * failure is logged and the verification finds nothing, so the IDE keeps running. It is thread-safe, because the
 * verifier keeps no state between verifications.
 */
public interface DslVerifier {

    /**
     * Verifies the state machines of [input] against its metamodels and its model, and the metamodels against its
     * attachable state machines. The findings of the machines come in the order of [VerificationInput.stateMachines].
     */
    public fun verify(input: VerificationInput): VerificationFindings
}

/** The default [DslVerifier] over the project verifier of dsl-tools. */
public fun DslVerifier(): DslVerifier = DefaultDslVerifier()

/**
 * The files of one project that are verified together, as a [DslReader] read them.
 * @property metamodel the read of the domain metamodel.
 * @property events the read of the event metamodel, or null if the project has none.
 * @property model the read of the model, or null if the project has none or it could not be read.
 * @property stateMachines the state machines to verify: every attached and every attachable state machine of the
 *   project that was read.
 * @property unreadMachines how many state machine texts of the project have no read, because they have syntax errors.
 *   While it is not zero, the set of attachable state machines is incomplete, and the verifier reports no name as
 *   unknown.
 */
public class VerificationInput(
    public val metamodel: ReadResult<Metamodel>,
    public val events: ReadResult<Metamodel>?,
    public val model: ReadResult<Model>?,
    public val stateMachines: List<MachineToVerify>,
    public val unreadMachines: Int = 0,
)

/**
 * A state machine to verify.
 * @property read the read of the state machine's text.
 * @property attachedObjectId the id of the object of the model the machine runs on, or null if it is not known. The
 *   checks of the attached object and of the values of the model need it. An attachable state machine has none.
 */
public class MachineToVerify(public val read: ReadResult<StateMachine>, public val attachedObjectId: String?)

/**
 * What a [DslVerifier] found.
 * @property stateMachines the findings of each state machine, in the order of [VerificationInput.stateMachines], each
 *   sorted with [Diagnostic.ORDER].
 * @property metamodel the findings in the text of the domain metamodel, sorted with [Diagnostic.ORDER].
 * @property events the findings in the text of the event metamodel, sorted with [Diagnostic.ORDER]. Empty for a
 *   project without one.
 */
public class VerificationFindings(
    public val stateMachines: List<List<Diagnostic>>,
    public val metamodel: List<Diagnostic> = emptyList(),
    public val events: List<Diagnostic> = emptyList(),
)

/**
 * The [DslVerifier] over a `ProjectVerifier` of dsl-tools. It turns the reads into the verifier's input, runs it and
 * turns its findings into the IDE's diagnostics with [ReadResults]. Each state machine text without a read is handed
 * to the verifier as a read that failed with a syntax error, which the verifier leaves out and counts as a machine it
 * could not read. The class holds no state.
 */
internal class DefaultDslVerifier : DslVerifier {

    override fun verify(input: VerificationInput): VerificationFindings {
        val nothing = VerificationFindings(input.stateMachines.map { emptyList() })
        val metamodel = input.metamodel.source ?: return nothing
        val events = input.events?.let { it.source ?: return nothing }
        val model = input.model?.let { it.source ?: return nothing }
        val machines = input.stateMachines.map { machine ->
            machine.read.source?.let { Verified(it, StateMachineInput(it.parse, machine.attachedObjectId)) }
        }
        val verifiable = machines.filterNotNull().map { it.input } + List(input.unreadMachines) { UNREAD }
        return try {
            val findings = ProjectVerifier().verify(ProjectInput(metamodel.parse, events?.parse, model?.parse, verifiable))
            VerificationFindings(
                stateMachines = machines.map { machine ->
                    machine?.let { ReadResults.diagnosticsOf(findings.of(it.input), it.source.textLength) }.orEmpty()
                },
                metamodel = ReadResults.diagnosticsOf(findings.metamodel, metamodel.textLength),
                events = events?.let { ReadResults.diagnosticsOf(findings.events, it.textLength) }.orEmpty(),
            )
        } catch (e: StackOverflowError) {
            failed(e, nothing)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            failed(e, nothing)
        }
    }

    /** Logs [failure] and returns [nothing], the findings of a verification that failed. */
    private fun failed(failure: Throwable, nothing: VerificationFindings): VerificationFindings {
        logger.error("The dsl-tools verifier failed", failure)
        return nothing
    }

    /**
     * A state machine that can be verified: the [source] its read kept and the [input] of the verifier made from it.
     */
    private class Verified(val source: DslSource<StateMachine>, val input: StateMachineInput)

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(DefaultDslVerifier::class.java)

        /**
         * The stand-in for a state machine text without a read: a read without a model that failed with a syntax
         * error. The verifier checks nothing of it and knows from it that its set of state machines is incomplete.
         */
        val UNREAD: StateMachineInput = StateMachineInput(
            ParseResult<StateMachine>(
                model = null,
                diagnostics = listOf(
                    DslDiagnostic(
                        SourceRange(SourcePosition(0, 1, 1), SourcePosition(0, 1, 1)),
                        DslSeverity.ERROR,
                        DiagnosticCode.SYNTAX_ERROR,
                        "The text of the state machine has syntax errors and was not read.",
                    ),
                ),
                sourceMap = SourceMap.EMPTY,
            ),
        )
    }
}
