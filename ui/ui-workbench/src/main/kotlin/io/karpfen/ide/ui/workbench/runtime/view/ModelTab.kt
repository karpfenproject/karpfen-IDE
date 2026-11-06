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
package io.karpfen.ide.ui.workbench.runtime.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.karpfen.ide.runtime.session.observation.ObservationMode
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme
import io.karpfen.ide.ui.workbench.runtime.ModelTreeRow
import io.karpfen.ide.ui.workbench.runtime.RunDetails
import io.karpfen.ide.ui.workbench.runtime.RunEntry
import io.karpfen.ide.ui.workbench.runtime.RuntimeState
import io.karpfen.ide.ui.workbench.runtime.RuntimeTexts

/**
 * The Model tab of the launched run [entry]: the observation switch and Pull, a status line with the mode, the sync
 * status and the revision, and the model tree. An object row opens and closes, a link selects its target, which the
 * list scrolls to; objects the runtime created are marked.
 */
@Composable
internal fun ModelTab(runtime: RuntimeState, entry: RunEntry, details: RunDetails) {
    val model by details.session.model.collectAsState()
    val rows by details.modelTree.rows.collectAsState()
    val colors = KarpfenTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Observation", color = colors.text, style = KarpfenTheme.typography.uiSmall)
            Switch(
                checked = model.mode == ObservationMode.ON,
                onCheckedChange = { runtime.setObservation(entry, it) },
                modifier = Modifier.testTag(RuntimeViewTestTags.OBSERVATION),
            )
            SmallButton("Pull", RuntimeViewTestTags.PULL) { runtime.pull(entry) }
            Text(
                "${RuntimeTexts.mode(model)} · ${RuntimeTexts.sync(model)}",
                color = colors.textMuted,
                style = KarpfenTheme.typography.uiSmall,
                modifier = Modifier.testTag(RuntimeViewTestTags.MODEL_STATUS),
            )
        }
        val listState = rememberLazyListState()
        val selectedIndex = rows.indexOfFirst { it is ModelTreeRow.Object && it.selected }
        LaunchedEffect(selectedIndex) {
            if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows, key = { it.key }) { row -> ModelRow(row, details) }
        }
    }
}

/** One row of the model tree, indented by its depth. */
@Composable
private fun ModelRow(row: ModelTreeRow, details: RunDetails) {
    val colors = KarpfenTheme.colors
    val indent = Modifier.padding(start = (INDENT * row.depth + 8).dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
    when (row) {
        is ModelTreeRow.Object -> Row(
            Modifier
                .fillMaxWidth()
                .background(if (row.selected) colors.selection else Color.Transparent)
                .clickable { if (row.expandable) details.modelTree.toggle(row.key) }
                .then(indent)
                .testTag(RuntimeViewTestTags.modelRow(row.key)),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val marker = when {
                !row.expandable -> " "
                row.expanded -> "▾"
                else -> "▸"
            }
            val name = row.id.ifEmpty { "(no id)" }
            val held = row.relation?.let { "$it: " }.orEmpty()
            Text("$marker $held$name : ${row.typeName}", color = colors.text, style = KarpfenTheme.typography.monospace)
            if (row.runtimeCreated) Text("created at run time", color = colors.accent, style = KarpfenTheme.typography.uiSmall)
        }
        is ModelTreeRow.Property -> Text(
            "${row.name} = ${row.value}",
            color = colors.text,
            style = KarpfenTheme.typography.monospace,
            modifier = indent.testTag(RuntimeViewTestTags.modelRow(row.key)),
        )
        is ModelTreeRow.Link -> Row(
            indent.testTag(RuntimeViewTestTags.modelRow(row.key)),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${row.relation} →", color = colors.textMuted, style = KarpfenTheme.typography.monospace)
            row.targets.forEach { target ->
                if (target.isEmpty()) {
                    Text("–", color = colors.textMuted, style = KarpfenTheme.typography.monospace)
                } else {
                    Text(
                        target,
                        color = colors.accent,
                        style = KarpfenTheme.typography.monospace,
                        modifier = Modifier.clickable { details.modelTree.select(target) }.testTag(RuntimeViewTestTags.link(target)),
                    )
                }
            }
        }
    }
}

/** The indentation of one level of the model tree in dp. */
private const val INDENT = 16
