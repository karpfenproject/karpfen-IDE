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
package io.karpfen.ide.app.launch

import io.karpfen.ide.runtime.host.OpenRuntimeHosts
import io.karpfen.ide.runtime.host.RuntimeHostFactory
import io.karpfen.ide.runtime.host.RuntimeHosts

/**
 * The runtime hosts of the IDE's process, which are closed when the process ends.
 *
 * The answer creates its hosts through [hosts] and knows which of them are still open. It adds a step to [atExit] in
 * the stage [ExitSequence.Stage.HOSTS] that closes those. A Docker host that is closed stops and removes its container.
 * The container of a workspace therefore goes away when the window is closed, when the process is asked to terminate,
 * for example by Ctrl+C on the command that started it, and when the process exits while a host is still stopping its
 * container. The process ends only when the step has returned. Whatever else has to stop before the hosts close, such
 * as the run of the headless mode, adds a step of the stage [ExitSequence.Stage.RUNS] to the same sequence, so it runs
 * first and never races the hosts.
 *
 * The step cannot run when the process is killed outright or the machine loses power. A container left behind that way
 * is adopted or removed by the next run in the same folder.
 *
 * @param hosts creates the hosts.
 * @param atExit the one sequence of the process that runs when it ends.
 */
internal fun runtimeHostsClosedAtExit(
    hosts: RuntimeHostFactory = RuntimeHosts(),
    atExit: ExitSequence = ExitSequence(),
): RuntimeHostFactory {
    val open = OpenRuntimeHosts(hosts)
    atExit.add(ExitSequence.Stage.HOSTS, "close the runtime hosts that are still open", open::close)
    return open
}
