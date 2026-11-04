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
package io.karpfen.ide.runtime.protocol.source

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the source fixtures the anchor tests stand on: [KotlinTemplateLiteral] finds the string literals of Kotlin
 * code past comments and character literals, splits quoted and triple-quoted templates into text and placeholders,
 * unescapes quoted strings, skips the strings nested in a placeholder's expression, joins concatenations and fails
 * naming a placeholder without a sample; [RuntimeSourceCheckout] reads files and names what is missing.
 */
class KotlinTemplateLiteralTest {

    @Test
    fun `a triple-quoted template is split into its text and its placeholders`() {
        val literal = KotlinTemplateLiteral.startingWith(RAW_SOURCE, "{\"objectId\":")

        assertEquals(listOf("ModelJsonWriter.quote(objectId)", "revision", "newValue"), literal.placeholders)
        assertEquals(
            """{"objectId":"a","revision":7,"value":{}}""",
            literal.interpolate(mapOf("ModelJsonWriter.quote(objectId)" to "\"a\"", "revision" to "7", "newValue" to "{}")),
        )
    }

    @Test
    fun `comments, character literals and nested strings are no literals of the code`() {
        val literals = KotlinTemplateLiteral.all(TRICKY_SOURCE)

        assertEquals(listOf("\"first\"", "\"\${list.joinToString(\",\") { \"[\$it]\" }}!\"", "\"last\""), literals.map { it.written })
        assertEquals(listOf("list.joinToString(\",\") { \"[\$it]\" }"), literals[1].placeholders)
    }

    @Test
    fun `a quoted template is unescaped as Kotlin does`() {
        val literal = KotlinTemplateLiteral.all("val x = \"a\\\"b\\\\c\\n\\t\\u00e9\\\$d\"").single()

        assertEquals("a\"b\\c\n\té\$d", literal.interpolate(emptyMap()))
    }

    @Test
    fun `quotes before the closing delimiter of a triple-quoted string belong to its content`() {
        val literal = KotlinTemplateLiteral.all("val x = \"\"\"say \"hi\"\"\"\"").single()

        assertEquals("say \"hi\"", literal.interpolate(emptyMap()))
    }

    @Test
    fun `a concatenation over several lines is one template`() {
        val literal = KotlinTemplateLiteral.concatenationStartingWith(CONCATENATED_SOURCE, "{\\\"phase\\\":")

        assertEquals(listOf("phase", "revision"), literal.placeholders)
        assertEquals("{\"phase\":running,\"revision\":3}", literal.interpolate(mapOf("phase" to "running", "revision" to "3")))
    }

    @Test
    fun `a placeholder without a sample and an ambiguous prefix are refused`() {
        val literal = KotlinTemplateLiteral.startingWith(RAW_SOURCE, "{\"objectId\":")

        val missing = assertFailsWith<IllegalArgumentException> { literal.interpolate(mapOf("revision" to "1")) }
        assertTrue("ModelJsonWriter.quote(objectId)" in missing.message.orEmpty(), missing.message)
        assertFailsWith<IllegalArgumentException> { KotlinTemplateLiteral.startingWith(TRICKY_SOURCE, "") }
    }

    @Test
    fun `the checkout reads files below its root and names a missing file`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("src/main/kotlin/io"))
        Files.writeString(root.resolve("src/main/kotlin/io/A.kt"), "class A")
        val checkout = RuntimeSourceCheckout(root)

        assertEquals("class A", checkout.server("io/A.kt"))
        val failure = assertFailsWith<IllegalStateException> { checkout.engine("io/B.kt") }
        assertTrue("execution-engine/src/main/kotlin/io/B.kt" in failure.message.orEmpty(), failure.message)
        assertTrue(SourceAnchor("src/main/kotlin/io/A.kt", "class").holdsIn(checkout))
    }

    private companion object {
        /** A runtime-like source with a triple-quoted payload template. */
        const val RAW_SOURCE = "fun payload() = \"\"\"{\"objectId\":\${ModelJsonWriter.quote(objectId)},\"revision\":\$revision," +
            "\"value\":\$newValue}\"\"\""

        /** A source whose comments, character literal and nested string must not count as literals of the code. */
        const val TRICKY_SOURCE = "val a = \"first\"\n" +
            "val quote = '\"'\n" +
            "val escaped = '\\''\n" +
            "val unicode = '\\u0022'\n" +
            "/* a block \"comment\" /* nested \" */ still */\n" +
            "val b = \"\${list.joinToString(\",\") { \"[\$it]\" }}!\"\n" +
            "val c = \"last\"\n"

        /** A source with a template written as a concatenation of quoted literals. */
        const val CONCATENATED_SOURCE = "val other = \"x\"\n" +
            "fun json() = \"{\\\"phase\\\":\$phase,\" +\n" +
            "    \"\\\"revision\\\":\$revision}\"\n" +
            "val after = \"y\"\n"
    }
}
