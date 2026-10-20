package io.karpfen.ide.core.workspace.document

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * A user-readable message for a failure to read or write [path] (I/O errors are values, decision 12): the file name
 * and the reason, without stack traces or exception class names where a reason is known.
 */
internal fun describeIoFailure(path: Path, failure: Exception): String {
    val name = path.fileName?.toString() ?: path.toString()
    val reason = when (failure) {
        is NoSuchFileException -> "the file does not exist"
        is AccessDeniedException -> "access denied"
        is FileSystemException -> failure.reason ?: failure.javaClass.simpleName
        else -> failure.message ?: failure.javaClass.simpleName
    }
    return "$name: $reason"
}
