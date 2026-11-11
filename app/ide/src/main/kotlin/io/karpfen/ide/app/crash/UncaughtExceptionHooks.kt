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
package io.karpfen.ide.app.crash

import java.awt.EventQueue

/** Where the JVM reports uncaught exceptions. This seam keeps `CrashHandling` testable without touching the JVM. */
internal fun interface UncaughtExceptionHooks {
    /** Routes uncaught exceptions to [handler]. */
    fun install(handler: Thread.UncaughtExceptionHandler)
}

/** The JVM's hooks: the default uncaught-exception handler and the AWT event-dispatch thread's own handler. */
internal class JvmUncaughtExceptionHooks : UncaughtExceptionHooks {

    /**
     * Makes [handler] the default uncaught-exception handler, which every thread without a handler of its own uses,
     * worker threads included. It also makes [handler] the event-dispatch thread's own handler. That one is set on the
     * event-dispatch thread itself, so exceptions thrown by event handlers reach [handler] even if a library installed
     * a thread-specific handler. The event-dispatch thread keeps running after reporting them.
     */
    override fun install(handler: Thread.UncaughtExceptionHandler) {
        Thread.setDefaultUncaughtExceptionHandler(handler)
        EventQueue.invokeLater { Thread.currentThread().uncaughtExceptionHandler = handler }
    }
}
