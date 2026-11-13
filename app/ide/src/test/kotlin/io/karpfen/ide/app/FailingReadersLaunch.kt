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
package io.karpfen.ide.app

import io.karpfen.ide.core.document.fixtures.SmallStackDslReader
import io.karpfen.ide.runtime.host.RuntimeHosts
import io.karpfen.ide.runtime.session.RuntimeSessions
import io.karpfen.ide.ui.workbench.defaultWorkbenchServices
import kotlin.system.exitProcess

/**
 * Starts the IDE as the application's `main` does, but with readers of dsl-tools that read on a small stack,
 * [SmallStackDslReader]. A state machine whose actions nest a few hundred levels, within the nesting limit of the
 * parsers, then makes them fail as a real reader failure does. dsl-tools logs a warning that reaches the Language Tools
 * Output. No text a user writes makes the readers of the real IDE fail, so a hands-on check fills the output this way.
 * The Gradle task `runWithFailingReaders` of this module starts it.
 */
public fun main(args: Array<String>) {
    val status = launch(
        args,
        application = { exitAfterMs ->
            IdeApplication(
                exitAfterMs,
                services = { output ->
                    defaultWorkbenchServices(
                        output,
                        dslReader = SmallStackDslReader(),
                        runtimeSessions = RuntimeSessions(),
                        runtimeHosts = RuntimeHosts(),
                    )
                },
            )
        },
    )
    exitProcess(status)
}
