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
package io.karpfen.ide.bridges.api

import io.karpfen.ide.runtime.protocol.message.WireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests the JSON paths and the JSON text of the bridges. */
class JsonTest {

    private val body = checkNotNull(JsonText.read("""{"up": true, "room": {"name": "living", "lamps": [{"watts": 40}, {"watts": 60}]}}"""))

    @Test
    fun `a path leads to members, into arrays and to the whole value`() {
        assertEquals(WireValue.WireBoolean(true), path("$.up").read(body))
        assertEquals(WireValue.WireString("living"), path("$.room.name").read(body))
        assertEquals(WireValue.WireNumber(60.0), path("$.room.lamps[1].watts").read(body))
        assertEquals(body, path("$").read(body))
    }

    @Test
    fun `a path that leads nowhere reads nothing`() {
        assertNull(path("$.down").read(body))
        assertNull(path("$.room.lamps[5]").read(body))
        assertNull(path("$.up.deeper").read(body))
    }

    @Test
    fun `only a text that starts with a dollar sign and is well formed is a path`() {
        assertNull(JsonPath.parse("up"))
        assertNull(JsonPath.parse("$.a..b"))
        assertNull(JsonPath.parse("$.a[x]"))
        assertEquals("$.a.b", JsonPath.parse("  $.a.b ")?.text)
        assertEquals(path("$.a"), path("$.a"))
    }

    @Test
    fun `JSON is written on one line with whole numbers as such`() {
        val value = JsonText.objectOf(
            "n" to WireValue.WireNumber(40.0),
            "f" to WireValue.WireNumber(2.5),
            "s" to WireValue.WireString("a \"b\""),
            "l" to WireValue.WireArray(listOf(WireValue.WireBoolean(false), WireValue.WireNull)),
            "bad" to WireValue.WireNumber(Double.NaN),
        )

        assertEquals("""{"n":40,"f":2.5,"s":"a \"b\"","l":[false,null],"bad":null}""", JsonText.write(value))
        assertEquals(value.copy(fields = value.fields.dropLast(1)), JsonText.read(JsonText.write(value.copy(fields = value.fields.dropLast(1)))))
    }

    @Test
    fun `pretty JSON is indented and reads back the same`() {
        val value = JsonText.objectOf("a" to WireValue.WireArray(listOf(WireValue.WireNumber(1.0))), "empty" to WireValue.WireObject(emptyList()))

        assertEquals("{\n  \"a\": [\n    1\n  ],\n  \"empty\": {}\n}", JsonText.writePretty(value))
        assertEquals(value, JsonText.read(JsonText.writePretty(value)))
    }

    @Test
    fun `a text that is not JSON reads as nothing`() {
        assertNull(JsonText.read("{ up: true }"))
        assertNull(JsonText.read(""))
    }

    private fun path(text: String): JsonPath = checkNotNull(JsonPath.parse(text)) { "$text is no path" }
}
