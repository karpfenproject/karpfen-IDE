package io.karpfen.ide.ui.workbench.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Content of a new environment descriptor `*.kenv.json` (documented on [FileTemplates]): the files of the directory's
 * current context, relative to the directory, so creating the descriptor keeps them in one context; each state machine
 * attached to the first object of its `ATTACHED TO` type, else to the first object, else to [PLACEHOLDER_OBJECT].
 * Without a context the descriptor names files after itself. Written with kotlinx.serialization, so every name is a
 * correctly escaped JSON string. Pure.
 */
internal object EnvironmentTemplate {

    /** The object key used when the context knows no object. */
    const val PLACEHOLDER_OBJECT: String = "object"

    /** Pretty-printing JSON, so the new descriptor reads well in any editor. */
    private val json = Json { prettyPrint = true }

    /** The descriptor of a new environment file [baseName] (without suffix) for [context], ending in a newline. */
    fun content(baseName: String, context: TemplateContext): String {
        val modeling = context.context
        val relative = { path: Path -> relativePath(context.directory, path) }
        val descriptor = buildJsonObject {
            put("metamodel", modeling?.metamodel?.let(relative) ?: "$baseName.kmeta")
            modeling?.events?.let { put("events", relative(it)) }
            put("model", modeling?.models?.firstOrNull()?.let(relative) ?: "$baseName.kmodel")
            val machines = modeling?.stateMachines?.map { it.file } ?: listOf(context.directory.resolve("$baseName.kstates"))
            put("stateMachines", JsonArray(machines.map { stateMachine(relative(it), attachedTo(it, context)) }))
        }
        return json.encodeToString(JsonObject.serializer(), descriptor) + "\n"
    }

    /** The key of the object the state machine [file] runs attached to (see [FileTemplates]). */
    fun attachedTo(file: Path, context: TemplateContext): String {
        val candidates = context.objects.filter { it.key.isNotBlank() }
        val type = context.stateMachineTypes[file]
        return candidates.firstOrNull { type != null && it.typeName == type }?.key
            ?: candidates.firstOrNull()?.key
            ?: PLACEHOLDER_OBJECT
    }

    /** The `stateMachines` entry of [file] attached to the object [attachedTo]. */
    private fun stateMachine(file: String, attachedTo: String): JsonObject =
        JsonObject(mapOf("file" to JsonPrimitive(file), "attachedTo" to JsonPrimitive(attachedTo)))

    /**
     * [path] relative to [directory] with `/` separators. A path on another file-system root, which `relativize`
     * rejects, can only be named by its absolute path.
     */
    private fun relativePath(directory: Path, path: Path): String = try {
        directory.relativize(path).joinToString("/")
    } catch (e: IllegalArgumentException) {
        path.toString()
    }
}
