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
package io.karpfen.ide.core.workspace.document

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * A user-readable message for a failure to read or write [path], for the results that carry I/O errors as values
 * instead of throwing them: the file name and the reason ([ioFailureReason]).
 */
internal fun describeIoFailure(path: Path, failure: Exception): String {
    val name = path.fileName?.toString() ?: path.toString()
    return "$name: ${ioFailureReason(failure)}"
}

/** Why [failure] happened, readable for users: without stack traces or exception class names where a reason is known. */
internal fun ioFailureReason(failure: Exception): String = when (failure) {
    is NoSuchFileException -> "the file does not exist"
    is AccessDeniedException -> failure.reason ?: "access denied"
    is FileSystemException -> failure.reason ?: failure.javaClass.simpleName
    else -> failure.message ?: failure.javaClass.simpleName
}
