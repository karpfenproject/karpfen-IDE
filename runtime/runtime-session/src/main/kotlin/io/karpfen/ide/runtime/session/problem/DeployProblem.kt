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
package io.karpfen.ide.runtime.session.problem

import io.karpfen.ide.core.document.Severity

/**
 * A finding of the deployment or of a running session: a preflight check of the plan, a failed call to the runtime,
 * an ended connection or something observed later. Immutable.
 *
 * @property severity how serious it is; an ERROR blocks a deployment or fails it.
 * @property code one of the stable codes of [DeployProblemCodes].
 * @property message the finding in plain words, naming what it concerns.
 * @property location where it points in the workspace, or null if it concerns no file.
 */
public data class DeployProblem(
    public val severity: Severity,
    public val code: String,
    public val message: String,
    public val location: SourceLocation?,
)
