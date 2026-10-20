package io.karpfen.ide.ui.foundation.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Small vector icons drawn with `ImageVector.Builder` (spec §6; no material-icons-extended). Constants: each icon is
 * built once on first access.
 *
 * All icons are 16 × 16 dp. File-type icons ([Folder], [File], [Kmeta], [Kmodel], [Kstates], [Json]) and severity icons
 * ([Error], [Warning], [Info]) carry their own colours: render them untinted (`Image`, or `Icon` with
 * `tint = Color.Unspecified`). Glyph icons ([Close], [ChevronRight], [ChevronDown], [Search]) are black and meant to be
 * tinted with a palette colour (`Icon(…, tint = KarpfenTheme.colors.textMuted)`).
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
