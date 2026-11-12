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
package io.karpfen.ide.runtime.session.plan

import io.karpfen.ide.core.document.Severity
import io.karpfen.ide.runtime.session.problem.DeployProblem
import io.karpfen.ide.runtime.session.problem.DeployProblemCodes

/**
 * Finds an object reachable from itself through `has` and `knows` relations. The runtime writes the targets of `knows`
 * relations as nested objects, so it cannot write such a model. Snapshots are refused, and changes that reach the loop
 * fail. The first keyed object found on a loop is reported at its declaration. If no object on the loop has a key, the
 * problem points at the model file.
 */
internal class ReferenceLoopCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val model = subject.model ?: return emptyList()
        val loop = ReferenceLoops.firstIn(model) ?: return emptyList()
        val reported = loop.firstKeyed
        val names = loop.path.joinToString(" to ") { it.key.ifEmpty { "an object of type '${it.typeName}' without a key" } }
        val subjectName = reported?.let { "'${it.key}'" } ?: "an object of type '${loop.start.typeName}'"
        return listOf(
            DeployProblem(
                Severity.ERROR,
                DeployProblemCodes.REFERENCE_LOOP,
                "Object $subjectName is reachable from itself through has and knows relations ($names); the runtime " +
                    "cannot write such a model.",
                reported?.let { subject.objectLocation(it.key) } ?: subject.modelFile?.let(subject::fileLocation),
            ),
        )
    }
}

/**
 * Finds keys that several objects of the runtime's index share. The runtime keeps the last one, and observation cannot
 * tell them apart.
 */
internal class DuplicateObjectKeyCheck : PlanCheck {
    override fun check(subject: PlanSubject): List<DeployProblem> {
        val keyed = subject.keyedObjects ?: return emptyList()
        return keyed.duplicates.map { (key, count) ->
            DeployProblem(
                Severity.WARNING,
                DeployProblemCodes.DUPLICATE_OBJECT_KEY,
                "$count objects share the key '$key'; the runtime keeps the last, and observation cannot tell them apart.",
                subject.objectLocation(key),
            )
        }
    }
}
