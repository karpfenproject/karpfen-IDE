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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The single writer of the documents' texts. It runs every change of an open document on the dispatcher of the
 * workspace's [scope], because an `EditableText` may only be changed from one thread. Such changes are a reload, a new
 * save point and a change of the conflict state. Callers that are already on that dispatcher run in place without a
 * dispatch. In the app that is the UI thread, in tests the test thread. I/O always happens outside the writer.
 */
internal class SingleWriter(scope: CoroutineScope) {

    /** The dispatcher of the workspace's scope. If the scope has none, the context does not change. */
    private val context = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

    /** Runs [mutation] on the writer's dispatcher and returns its result. */
    suspend fun <T> run(mutation: () -> T): T = withContext(context) { mutation() }
}
