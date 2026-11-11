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
package io.karpfen.ide.ui.foundation.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Small vector icons drawn with `ImageVector.Builder`, so the IDE needs no material-icons-extended dependency. Each
 * icon is a constant that is built once, on first access.
 *
 * All icons are 16 by 16 dp. The file-type icons [Folder], [File], [Kmeta], [Kmodel], [Kstates] and [Json] and the
 * severity icons [Error], [Warning] and [Info] carry their own colours. Render them untinted, with `Image` or with
 * `Icon` and `tint = Color.Unspecified`. The glyph icons [Close], [ChevronRight], [ChevronDown] and [Search] are black
 * and meant to be tinted with a palette colour, for example
 * `Icon(KarpfenIcons.Close, null, tint = KarpfenTheme.colors.textMuted)`.
 */
public object KarpfenIcons {

    private val folderIcon by lazy(IconShapes::folder)
    private val fileIcon by lazy(IconShapes::file)
    private val kmetaIcon by lazy(IconShapes::kmeta)
    private val kmodelIcon by lazy(IconShapes::kmodel)
    private val kstatesIcon by lazy(IconShapes::kstates)
    private val jsonIcon by lazy(IconShapes::json)
    private val errorIcon by lazy(IconShapes::error)
    private val warningIcon by lazy(IconShapes::warning)
    private val infoIcon by lazy(IconShapes::info)
    private val closeIcon by lazy(IconShapes::close)
    private val chevronRightIcon by lazy(IconShapes::chevronRight)
    private val chevronDownIcon by lazy(IconShapes::chevronDown)
    private val searchIcon by lazy(IconShapes::search)

    /** A folder. */
    public val Folder: ImageVector get() = folderIcon

    /** A generic file. */
    public val File: ImageVector get() = fileIcon

    /** A `.kmeta` file. */
    public val Kmeta: ImageVector get() = kmetaIcon

    /** A `.kmodel` file. */
    public val Kmodel: ImageVector get() = kmodelIcon

    /** A `.kstates` file. */
    public val Kstates: ImageVector get() = kstatesIcon

    /** A JSON file (environment descriptor, layout). */
    public val Json: ImageVector get() = jsonIcon

    /** Error severity. */
    public val Error: ImageVector get() = errorIcon

    /** Warning severity. */
    public val Warning: ImageVector get() = warningIcon

    /** Info severity. */
    public val Info: ImageVector get() = infoIcon

    /** Close (tab close button). */
    public val Close: ImageVector get() = closeIcon

    /** Collapsed tree node. */
    public val ChevronRight: ImageVector get() = chevronRightIcon

    /** Expanded tree node. */
    public val ChevronDown: ImageVector get() = chevronDownIcon

    /** Search (find bar). */
    public val Search: ImageVector get() = searchIcon
}
