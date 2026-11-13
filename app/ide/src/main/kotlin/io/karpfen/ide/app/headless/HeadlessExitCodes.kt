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
package io.karpfen.ide.app.headless

/** The exit statuses of the headless mode, those of `validate` and the cases that `run` adds to them. */
internal object HeadlessExitCodes {
    /** The validation found no error, or the usage was printed on request. */
    const val OK: Int = 0

    /** The validation found at least one error, also when the errors keep a run from starting. */
    const val ERRORS_FOUND: Int = 1

    /** The command line is wrong: an unknown option, a missing folder, a descriptor or bridge file that is not one. */
    const val USAGE: Int = 2

    /**
     * The run could not start or ended on its own: the deployment was blocked or failed, the runtime could not be
     * reached, or the engine died. A failure of the tool itself ends with it too.
     */
    const val FAILURE: Int = 3
}
