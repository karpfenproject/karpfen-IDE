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
package io.karpfen.ide.runtime.protocol.message

import io.karpfen.ide.runtime.protocol.message.WireValue.WireArray
import io.karpfen.ide.runtime.protocol.message.WireValue.WireBoolean
import io.karpfen.ide.runtime.protocol.message.WireValue.WireNull
import io.karpfen.ide.runtime.protocol.message.WireValue.WireNumber
import io.karpfen.ide.runtime.protocol.message.WireValue.WireObject
import io.karpfen.ide.runtime.protocol.message.WireValue.WireString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers [RuntimeJson]: reading the runtime's pushes and snapshots with their field order and repeated names, every
 * escape and surrogate pair, numbers in every form the runtime writes including its bare non-finite literals, the
 * refusal of everything outside JSON with the offset where reading failed, the nesting limit, and quoting by the
 * runtime writer's rule.
 */
class RuntimeJsonTest {

    /** The value of [text], failing the test if it is malformed. */
    private fun read(text: String): WireValue = assertIs<JsonReading.Read>(RuntimeJson.read(text), text).value

    /** The malformed reading of [text], failing the test if it reads. */
    private fun malformed(text: String): JsonReading.Malformed = assertIs<JsonReading.Malformed>(RuntimeJson.read(text), text)

    @Test
    fun `a push of the runtime is read with its members in order`() {
        val push = """{"environmentKey":"env-1761560123456","clientId":"karpfen-ide-4f2c9a1b7e03","messageType":"objectChanged",""" +
            """"payload":{"objectId":"turtlePosition","revision":58,"value":{"__id__": "turtlePosition", "__type__": "Vector", "x": 5, "y": 5.3}}}"""

        val envelope = assertIs<WireObject>(read(push))

        assertEquals(listOf("environmentKey", "clientId", "messageType", "payload"), envelope.fields.map { it.name })
        val payload = assertIs<WireObject>(envelope["payload"])
        assertEquals(WireNumber(58.0), payload["revision"])
        assertEquals(
            WireObject(
                listOf(
                    WireField("__id__", WireString("turtlePosition")),
                    WireField("__type__", WireString("Vector")),
                    WireField("x", WireNumber(5.0)),
                    WireField("y", WireNumber(5.3)),
                ),
            ),
            payload["value"],
        )
    }

    @Test
    fun `repeated names are kept in order and get answers the first`() {
        val value = assertIs<WireObject>(read("""{"a": 1, "b": true, "a": "second"}"""))

        assertEquals(listOf("a", "b", "a"), value.fields.map { it.name })
        assertEquals(WireNumber(1.0), value["a"])
        assertEquals(null, value["missing"])
    }

    @Test
    fun `values of every kind are read, nested and empty ones included`() {
        assertEquals(
            WireArray(
                listOf(
                    WireNull, WireBoolean(true), WireBoolean(false), WireString(""), WireArray(emptyList()), WireObject(emptyList()),
                    WireArray(listOf(WireObject(listOf(WireField("k", WireNull))))),
                ),
            ),
            read(""" [ null , true,false,"" , [ ] , { } , [ { "k" : null } ] ] """),
        )
        assertEquals(WireString("top"), read("\"top\""))
        assertEquals(WireNumber(7.0), read("\t\r\n7\n"))
    }

    @Test
    fun `numbers are read as doubles in every JSON form`() {
        mapOf(
            "0" to 0.0, "-0" to -0.0, "5" to 5.0, "-12" to -12.0, "0.2" to 0.2, "5.3" to 5.3, "1.0E20" to 1.0E20,
            "1e3" to 1000.0, "2E-2" to 0.02, "-1.5e+2" to -150.0, "1000000000000000000" to 1.0E18,
        ).forEach { (text, expected) -> assertEquals(WireNumber(expected), read(text), text) }
    }

    @Test
    fun `the runtime's bare non-finite numbers are read`() {
        assertEquals(
            WireArray(listOf(WireNumber(Double.NaN), WireNumber(Double.POSITIVE_INFINITY), WireNumber(Double.NEGATIVE_INFINITY))),
            read("[NaN, Infinity, -Infinity]"),
        )
        assertEquals(WireNumber(Double.NaN), assertIs<WireObject>(read("""{"x": NaN}"""))["x"])
    }

    @Test
    fun `every escape and surrogate pairs are resolved`() {
        assertEquals(
            WireString("quote \" backslash \\ slash / \b \u000C \n \r \t \u0001 é \uD83D\uDE00"),
            read(""""quote \" backslash \\ slash \/ \b \f \n \r \t \u0001 \u00e9 \uD83D\uDE00""""),
        )
        assertEquals(WireString("100 W – full power"), read("\"100 W – full power\""))
    }

    @Test
    fun `text outside the runtime's JSON is malformed at the offset where reading failed`() {
        mapOf(
            "" to 0,
            "   " to 3,
            "{\"a\": 1,}" to 8,
            "[1, 2,]" to 6,
            "{a: 1}" to 1,
            "{'a': 1}" to 1,
            "[1] // comment" to 4,
            "[1] [2]" to 4,
            "/* c */ 1" to 0,
            "01" to 1,
            "1." to 2,
            "-" to 1,
            "1e" to 2,
            ".5" to 0,
            "+1" to 0,
            "nan" to 0,
            "Inf" to 0,
            "-NaN" to 1,
            "tru" to 0,
            "\"open" to 5,
            "\"tab\there\"" to 4,
            "\"bad \\x escape\"" to 5,
            "\"short \\u12\"" to 7,
            "{\"a\" 1}" to 5,
            "{\"a\": 1 \"b\": 2}" to 8,
            "[1 2]" to 3,
            "\u000B1" to 0,
        ).forEach { (text, offset) ->
            assertEquals(offset, malformed(text).offset, "offset in '$text': ${malformed(text).reason}")
        }
    }

    @Test
    fun `nesting is read up to the limit and refused beyond it`() {
        val deepest = "[".repeat(RuntimeJson.MAX_DEPTH) + "]".repeat(RuntimeJson.MAX_DEPTH)
        assertIs<WireArray>(read(deepest))
        val tooDeep = "[".repeat(RuntimeJson.MAX_DEPTH + 1) + "]".repeat(RuntimeJson.MAX_DEPTH + 1)
        assertEquals(RuntimeJson.MAX_DEPTH, malformed(tooDeep).offset)
        val objects = "{\"a\":".repeat(RuntimeJson.MAX_DEPTH + 1) + "1" + "}".repeat(RuntimeJson.MAX_DEPTH + 1)
        assertIs<JsonReading.Malformed>(RuntimeJson.read(objects))
    }

    @Test
    fun `quoting escapes exactly quote, backslash and the control characters`() {
        assertEquals("\"plain\"", RuntimeJson.quote("plain"))
        assertEquals("\"\"", RuntimeJson.quote(""))
        assertEquals(""""a \"b\" \\ c"""", RuntimeJson.quote("a \"b\" \\ c"))
        assertEquals(""""\b\f\n\r\t"""", RuntimeJson.quote("\b\u000C\n\r\t"))
        assertEquals(""""\u0000\u0001\u001f"""", RuntimeJson.quote("\u0000\u0001\u001F"))
        assertEquals("\"/ é – \u007F \u2028 \uD83D\uDE00\"", RuntimeJson.quote("/ é – \u007F \u2028 \uD83D\uDE00"), "everything else stays as it is")
    }

    @Test
    fun `quoted text reads back to itself`() {
        val texts = listOf("", "living room", "100 W – full power", "\"\\\n\u0000\u001F/", (0..0x7F).map(Int::toChar).joinToString(""))
        texts.forEach { text -> assertEquals(WireString(text), read(RuntimeJson.quote(text)), text) }
    }
}
