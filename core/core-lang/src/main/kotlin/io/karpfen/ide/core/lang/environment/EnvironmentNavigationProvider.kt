package io.karpfen.ide.core.lang.environment

import io.karpfen.ide.core.document.environment.DescriptorFileRole
import io.karpfen.ide.core.document.environment.ValueShape
import io.karpfen.ide.core.document.json.JsonNode
import io.karpfen.ide.core.lang.Location
import io.karpfen.ide.core.lang.feature.FeatureRequest
import io.karpfen.ide.core.lang.feature.NavigationProvider

/**
 * Navigation in environment descriptors, by the [DescriptorSlot] at the caret and the [schema]: from a file path to the
 * start of the file it names (if the workspace has it), from an `attachedTo` value to the declaration of that object in
 * the descriptor's model (its key in `make object "key":"Type"`). A descriptor declares nothing others refer to, so its
 * references are the same targets.
 */
internal class EnvironmentNavigationProvider(
    private val trees: DescriptorTrees,
    private val caret: DescriptorCaret,
    private val schema: ValueShape.Record,
) : NavigationProvider {

    override fun definition(request: FeatureRequest, offset: Int): List<Location> {
        val text = request.document.text
        val slot = caret.slotAt(trees.of(text), text.text, offset) as? DescriptorSlot.Value ?: return emptyList()
        val value = (slot.node as? JsonNode.StringNode)?.value ?: return emptyList()
        val project = DescriptorProject(request)
        val target = when (slot.shape) {
            is ValueShape.FilePath -> project.locationOf(value)
            ValueShape.ObjectKey -> project.fileNamed(DescriptorFileRole.MODEL, slot.root, schema)?.let { project.objectLocation(it, value) }
            else -> null
        }
        return listOfNotNull(target)
    }

    override fun references(request: FeatureRequest, offset: Int): List<Location> = definition(request, offset)
}
