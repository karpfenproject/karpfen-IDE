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
package io.karpfen.ide.runtime.protocol.api

/**
 * The result of a call to the runtime. It holds the value, or the reason why there is none. Calls never throw for
 * runtime failures.
 */
public sealed interface RuntimeResult<out T> {
    /**
     * The call succeeded.
     *
     * @property value what the runtime answered, read.
     */
    public data class Ok<out T>(public val value: T) : RuntimeResult<T>

    /**
     * The call failed.
     *
     * @property failure why.
     */
    public data class Failed(public val failure: RuntimeFailure) : RuntimeResult<Nothing>
}

/** Maps the value of a success with [transform]. A failure stays as it is. */
public inline fun <T, R> RuntimeResult<T>.map(transform: (T) -> R): RuntimeResult<R> =
    when (this) {
        is RuntimeResult.Ok -> RuntimeResult.Ok(transform(value))
        is RuntimeResult.Failed -> this
    }

/**
 * Returns the result of [transform] for the value of a success. The transform may fail itself. A failure stays as it
 * is.
 */
public inline fun <T, R> RuntimeResult<T>.flatMap(transform: (T) -> RuntimeResult<R>): RuntimeResult<R> =
    when (this) {
        is RuntimeResult.Ok -> transform(value)
        is RuntimeResult.Failed -> this
    }

/** The value of a success, or null for a failure. */
public fun <T> RuntimeResult<T>.getOrNull(): T? =
    when (this) {
        is RuntimeResult.Ok -> value
        is RuntimeResult.Failed -> null
    }

/** Runs [action] with the failure if this result is one, and returns this result unchanged. */
public inline fun <T> RuntimeResult<T>.onFailure(action: (RuntimeFailure) -> Unit): RuntimeResult<T> {
    if (this is RuntimeResult.Failed) action(failure)
    return this
}
