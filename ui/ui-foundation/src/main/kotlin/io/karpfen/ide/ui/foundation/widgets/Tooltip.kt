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
package io.karpfen.ide.ui.foundation.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.karpfen.ide.ui.foundation.theme.KarpfenTheme

/**
 * Shows [tooltip] near the pointer after the pointer has rested on [content] for [TooltipDefaults.DELAY_MILLIS]. No
 * tooltip is shown when [tooltip] is null. [modifier] applies to the box around [content] in both cases. It uses the
 * desktop `TooltipArea`, which Compose still marks experimental, because no stable desktop tooltip exists.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTooltip(tooltip: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (tooltip == null) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        modifier = modifier,
        tooltip = {
            val shape = RoundedCornerShape(4.dp)
            Surface(
                shape = shape,
                color = KarpfenTheme.colors.panel,
                shadowElevation = 4.dp,
                modifier = Modifier.border(1.dp, KarpfenTheme.colors.border, shape),
            ) {
                Text(
                    text = tooltip,
                    style = KarpfenTheme.typography.uiSmall,
                    color = KarpfenTheme.colors.text,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = TooltipDefaults.DELAY_MILLIS,
        content = content,
    )
}

/** Timing of [WithTooltip]. */
internal object TooltipDefaults {
    /** Hover time before a tooltip appears. */
    const val DELAY_MILLIS: Int = 600
}
