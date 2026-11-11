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
package io.karpfen.ide.tools.cli

/** Exit statuses of the karpfen command-line tool. */
public object ExitCodes {
    /** The usage was printed, or the validation found no ERROR. */
    public const val OK: Int = 0

    /** The validation found at least one ERROR. A karpfen file or directory that could not be read is an ERROR too. */
    public const val ERRORS_FOUND: Int = 1

    /** Invalid command line, or the directory to validate does not exist. */
    public const val USAGE: Int = 2

    /** The validation failed unexpectedly, which is a defect. The cause is logged. */
    public const val FAILURE: Int = 3
}
