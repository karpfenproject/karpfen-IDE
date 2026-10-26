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
package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dependency direction inside modules is acyclic: within each core and UI module, the packages of the production code
 * must not depend on each other in a cycle. A package depends on another package of the same module when one of its
 * files imports (or fully-qualified references) a declaration of it; the declaring package of a name is the longest
 * package of the module that contains it.
 */
class PackageCycleTest {

    @Test
    fun `the packages of each core and UI module form no dependency cycle`() {
        val modules = KarpfenModules.coreModules + KarpfenModules.uiModules
        assertTrue(KarpfenModules.coreModules.isNotEmpty() && KarpfenModules.uiModules.isNotEmpty(), "is KarpfenModules broken?")
        val cycles = modules.flatMap { module ->
            PackageGraph.ofModule(module).cycles().map { cycle -> "$module: ${cycle.sorted().joinToString(" <-> ")}" }
        }
        assertTrue(cycles.isEmpty(), "Package cycles inside core and UI modules: $cycles")
    }

    /**
     * Guards against a vacuous rule with known edges that must be seen: the built-in language registry wires the
     * adapters, and the editor state holds the caret model.
     */
    @Test
    fun `the graph is built from the real packages`() {
        val document = PackageGraph.ofModule("core/core-document")
        assertTrue(
            "io.karpfen.ide.core.document.antlr.lex" in document.edges["io.karpfen.ide.core.document.registry"].orEmpty(),
            "expected registry -> antlr.lex, got ${document.edges}",
        )
        val editor = PackageGraph.ofModule("ui/ui-editor")
        assertTrue(
            "io.karpfen.ide.ui.editor.model.caret" in editor.edges["io.karpfen.ide.ui.editor.state"].orEmpty(),
            "expected state -> model.caret, got ${editor.edges}",
        )
    }

    @Test
    fun `cycle detection finds direct and indirect cycles`() {
        val graph = PackageGraph(
            nodes = setOf("a", "b", "c", "d", "e"),
            edges = mapOf("a" to setOf("b"), "b" to setOf("c"), "c" to setOf("a"), "d" to setOf("e")),
        )
        assertEquals(listOf(setOf("a", "b", "c")), graph.cycles())
        assertEquals(emptyList(), PackageGraph(setOf("x", "y"), mapOf("x" to setOf("y"))).cycles())
    }
}

/**
 * A package dependency graph.
 *
 * @property nodes the packages.
 * @property edges maps a package to the packages it depends on.
 */
internal data class PackageGraph(val nodes: Set<String>, val edges: Map<String, Set<String>>) {

    /** The strongly connected components with more than one package (Tarjan), each a cycle. */
    fun cycles(): List<Set<String>> {
        var counter = 0
        val index = HashMap<String, Int>()
        val lowLink = HashMap<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = HashSet<String>()
        val components = ArrayList<Set<String>>()

        fun connect(node: String) {
            index[node] = counter
            lowLink[node] = counter
            counter++
            stack.addLast(node)
            onStack += node
            for (next in edges[node].orEmpty()) {
                if (next !in index) {
                    connect(next)
                    lowLink[node] = minOf(lowLink.getValue(node), lowLink.getValue(next))
                } else if (next in onStack) {
                    lowLink[node] = minOf(lowLink.getValue(node), index.getValue(next))
                }
            }
            if (lowLink[node] == index[node]) {
                val component = HashSet<String>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                if (component.size > 1) components += component
            }
        }

        nodes.sorted().forEach { if (it !in index) connect(it) }
        return components
    }

    companion object {
        /** The graph of the production packages of [module] (a module directory such as `ui/ui-editor`). */
        fun ofModule(module: String): PackageGraph = of(KarpfenModules.mainFiles.filter { it.moduleDir == module })

        /** The graph of the packages of [files] (one module) and the dependencies between them. */
        fun of(files: List<KoFileDeclaration>): PackageGraph {
            val packages = files.map { it.packageName }.toSet()
            val edges = HashMap<String, MutableSet<String>>()
            for (file in files) {
                val names = file.imports.map { it.name } + file.qualifiedReferences.map { it.name }
                for (name in names) {
                    val target = packages.filter { name.isWithin(it) }.maxByOrNull { it.length } ?: continue
                    if (target != file.packageName) edges.getOrPut(file.packageName) { HashSet() } += target
                }
            }
            return PackageGraph(packages, edges)
        }
    }
}
