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
package io.karpfen.ide.ui.workbench.testing

import io.karpfen.dsl.states.KstatesFeature
import io.karpfen.ide.ui.workbench.settings.ProjectSettings
import io.karpfen.ide.ui.workbench.settings.ProjectSettingsFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the project file of the folder [root] with [features] switched on and every other setting at its default, as
 * the IDE writes it after the user switched them on in the Run menu. Tests over folders whose state machines use the
 * optional constructs of KStates, such as the examples, call it before they open the folder, so that the constructs
 * are allowed there.
 */
fun switchOnLanguageFeatures(root: Path, features: Set<KstatesFeature> = KstatesFeature.ALL) {
    Files.writeString(root.resolve(ProjectSettings.FILE_NAME), ProjectSettingsFile.format(ProjectSettings(languageFeatures = features)))
}
