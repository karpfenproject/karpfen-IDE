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
package io.karpfen.ide.ui.workbench.welcome.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/** The empty state shown without an open folder: "Open Folder…" and the recent folders. */
@Composable
internal fun WelcomeView(
    recentFolders: List<String>,
    onOpenFolder: () -> Unit,
    onOpenRecent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KarpfenTheme.colors
    Column(
        modifier.fillMaxSize().background(colors.window).padding(32.dp).testTag(WelcomeTestTags.VIEW),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("karpfen IDE", style = KarpfenTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenFolder, modifier = Modifier.testTag(WelcomeTestTags.OPEN_FOLDER)) { Text("Open Folder…") }
        if (recentFolders.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Recent folders", style = KarpfenTheme.typography.uiSmall, color = colors.textMuted)
            Spacer(Modifier.height(8.dp))
            recentFolders.forEach { folder ->
                Text(
                    text = folder,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .clickable(role = Role.Button) { onOpenRecent(folder) }
                        .padding(vertical = 4.dp)
                        .testTag(WelcomeTestTags.recentFolder(folder)),
                )
            }
        }
    }
}
