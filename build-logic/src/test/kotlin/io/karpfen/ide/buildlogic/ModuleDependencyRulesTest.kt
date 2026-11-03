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
package io.karpfen.ide.buildlogic

import io.karpfen.ide.buildlogic.ModuleDependencyRules.DeclaredDependency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [ModuleDependencyRules]: the allowed directions between the layers, the group wildcard, the encoding of
 * declared dependencies and the violation messages, including forbidden project dependencies that only show up on a
 * resolved classpath, such as those added in a module's own `afterEvaluate` block or by `withDependencies` after the
 * declarations were scanned.
 */
class ModuleDependencyRulesTest {

    @Test
    fun `core must not depend on ui`() {
        assertFalse(ModuleDependencyRules.isAllowed(":core:core-text", ":ui:ui-editor"))
    }

    @Test
    fun `ui may depend on core`() {
        assertTrue(ModuleDependencyRules.isAllowed(":ui:ui-editor", ":core:core-text"))
    }

    @Test
    fun `core may depend on core only`() {
        assertTrue(ModuleDependencyRules.isAllowed(":core:core-document", ":core:core-text"))
        listOf(":notation:notation-diagram", ":runtime:runtime-protocol", ":bridges:bridge-api", ":app:ide", ":tools:cli")
            .forEach { assertFalse(ModuleDependencyRules.isAllowed(":core:core-lang", it), it) }
    }

    @Test
    fun `notation may depend on core but core not on notation`() {
        assertTrue(ModuleDependencyRules.isAllowed(":notation:notation-diagram", ":core:core-workspace"))
        assertTrue(ModuleDependencyRules.isAllowed(":notation:notation-sync", ":notation:notation-diagram"))
        assertFalse(ModuleDependencyRules.isAllowed(":core:core-workspace", ":notation:notation-diagram"))
        assertFalse(ModuleDependencyRules.isAllowed(":notation:notation-sync", ":runtime:runtime-session"))
    }

    @Test
    fun `bridges may reach runtime-protocol but no other runtime module`() {
        assertTrue(ModuleDependencyRules.isAllowed(":bridges:bridge-api", ":runtime:runtime-protocol"))
        assertTrue(ModuleDependencyRules.isAllowed(":bridges:bridge-mqtt", ":bridges:bridge-mapping"))
        assertFalse(ModuleDependencyRules.isAllowed(":bridges:bridge-api", ":runtime:runtime-session"))
        assertFalse(ModuleDependencyRules.isAllowed(":bridges:bridge-api", ":ui:ui-foundation"))
    }

    @Test
    fun `runtime, ui, app and cli follow the matrix`() {
        assertTrue(ModuleDependencyRules.isAllowed(":runtime:runtime-session", ":core:core-workspace"))
        assertFalse(ModuleDependencyRules.isAllowed(":runtime:runtime-host", ":bridges:bridge-api"))
        assertTrue(ModuleDependencyRules.isAllowed(":ui:ui-workbench", ":runtime:runtime-host"))
        assertFalse(ModuleDependencyRules.isAllowed(":ui:ui-workbench", ":app:ide"))
        assertTrue(ModuleDependencyRules.isAllowed(":app:ide", ":ui:ui-workbench"))
        assertTrue(ModuleDependencyRules.isAllowed(":tools:cli", ":runtime:runtime-session"))
        assertFalse(ModuleDependencyRules.isAllowed(":tools:cli", ":ui:ui-foundation"))
        assertFalse(ModuleDependencyRules.isAllowed(":architecture-tests", ":core:core-text"))
    }

    @Test
    fun `the runtime protocol depends on no other module while the rest of the runtime may use it and core`() {
        assertEquals(
            ModuleDependencyRules.Rule(":runtime:runtime-protocol", emptyList()),
            ModuleDependencyRules.ruleFor(":runtime:runtime-protocol"),
        )
        listOf(":core:core-text", ":core:core-workspace", ":runtime:runtime-session", ":runtime:runtime-host", ":bridges:bridge-api")
            .forEach { assertFalse(ModuleDependencyRules.isAllowed(":runtime:runtime-protocol", it), it) }
        assertTrue(ModuleDependencyRules.isAllowed(":runtime:runtime-protocol", ":runtime:runtime-protocol"), "its own test fixtures")
        assertTrue(ModuleDependencyRules.isAllowed(":runtime:runtime-session", ":runtime:runtime-protocol"))
        assertTrue(ModuleDependencyRules.isAllowed(":runtime:runtime-host", ":runtime:runtime-protocol"))
        assertTrue(ModuleDependencyRules.isAllowed(":runtime:runtime-session", ":core:core-workspace"))
        assertEquals(
            listOf(
                "implementation(project(\":core:core-text\")) is not allowed: " +
                    ":runtime:runtime-protocol may depend on no project dependencies",
            ),
            ModuleDependencyRules.violations(":runtime:runtime-protocol", listOf(DeclaredDependency("implementation", ":core:core-text"))),
        )
    }

    @Test
    fun `group wildcard does not match a sibling group with the same prefix`() {
        assertFalse(ModuleDependencyRules.matches(":core:*", ":corelib:x"))
        assertFalse(ModuleDependencyRules.matches(":core:*", ":core"))
        assertTrue(ModuleDependencyRules.matches(":core:*", ":core:core-text"))
    }

    @Test
    fun `violations name the offending declaration and the allowed targets`() {
        val violations = ModuleDependencyRules.violations(
            ":core:core-text",
            listOf(
                DeclaredDependency("implementation", ":ui:ui-foundation"),
                DeclaredDependency("testImplementation", ":core:core-document"),
            ),
        )
        assertEquals(
            listOf("implementation(project(\":ui:ui-foundation\")) is not allowed: :core:* may depend on :core:*"),
            violations,
        )
    }

    @Test
    fun `forbidden project dependencies that only show up on a resolved classpath are reported`() {
        val violations = ModuleDependencyRules.violations(
            ":core:core-text",
            dependencies = emptyList(),
            resolved = listOf(
                DeclaredDependency("compileClasspath", ":ui:ui-foundation"),
                DeclaredDependency("runtimeClasspath", ":ui:ui-foundation"),
                DeclaredDependency("testCompileClasspath", ":core:core-document"),
            ),
        )
        assertEquals(
            listOf(
                "project(\":ui:ui-foundation\") on compileClasspath, runtimeClasspath is not allowed: " +
                    ":core:* may depend on :core:*",
            ),
            violations,
        )
    }

    @Test
    fun `a forbidden declaration is not reported again for the classpaths it reaches`() {
        val violations = ModuleDependencyRules.violations(
            ":core:core-text",
            dependencies = listOf(DeclaredDependency("implementation", ":ui:ui-foundation")),
            resolved = listOf(
                DeclaredDependency("compileClasspath", ":ui:ui-foundation"),
                DeclaredDependency("runtimeClasspath", ":ui:ui-foundation"),
            ),
        )
        assertEquals(
            listOf("implementation(project(\":ui:ui-foundation\")) is not allowed: :core:* may depend on :core:*"),
            violations,
        )
    }

    @Test
    fun `modules outside the matrix are rejected`() {
        val violations = ModuleDependencyRules.violations(":experimental:foo", emptyList())
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("not covered"))
    }

    @Test
    fun `declared dependencies survive encoding`() {
        val dependency = DeclaredDependency("testRuntimeOnly", ":core:core-text")
        assertEquals(dependency, DeclaredDependency.decode(dependency.encode()))
    }
}
