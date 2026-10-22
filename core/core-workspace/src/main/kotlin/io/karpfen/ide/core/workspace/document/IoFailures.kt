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
