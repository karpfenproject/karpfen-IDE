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
package io.karpfen.ide.runtime.protocol.catalogue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Covers [UploadRejection]. The body of a refused upload parses into the upload and its errors and renders back to the
 * same text. Every upload the runtime names is read, also a state machine attached to a key with blanks and quotes. A
 * message may hold parentheses, and the code is the last group in parentheses. Any text that deviates from the form
 * parses to null, and the values refuse what the form cannot express.
 */
class UploadRejectionTest {

    @Test
    fun `the body of a refused model parses into its errors and renders back to the same text`() {
        val body = "Upload of the model rejected: 2 errors\n" +
            "2:23: 'fast' is not a number, but prop 'speed' is of type 'number' (kmodel.invalid-number)\n" +
            "3:11: Class type 'Robot' has no property 'colour' (object 'turtle') (kmodel.unknown-property)"

        val rejection = UploadRejection.parse(body)

        assertEquals(
            UploadRejection(
                RejectedUpload.Model,
                listOf(
                    UploadError(2, 23, "'fast' is not a number, but prop 'speed' is of type 'number'", "kmodel.invalid-number"),
                    UploadError(3, 11, "Class type 'Robot' has no property 'colour' (object 'turtle')", "kmodel.unknown-property"),
                ),
            ),
            rejection,
        )
        assertEquals(body, rejection?.render())
    }

    @Test
    fun `every upload the runtime names is read, with one error written in the singular`() {
        val uploads = mapOf(
            "metamodel" to RejectedUpload.Metamodel,
            "model" to RejectedUpload.Model,
            "event definitions" to RejectedUpload.EventDefinitions,
            "attachable state machine" to RejectedUpload.AttachableStateMachine,
            "state machine for 'lamp'" to RejectedUpload.StateMachine("lamp"),
            "state machine for 'living room's lamp'" to RejectedUpload.StateMachine("living room's lamp"),
            "state machine for ''" to RejectedUpload.StateMachine(""),
        )

        uploads.forEach { (words, upload) ->
            val body = "Upload of the $words rejected: 1 error\n1:1: KMeta content defines no type (kmeta.no-types)"

            assertEquals(UploadRejection(upload, listOf(UploadError(1, 1, "KMeta content defines no type", "kmeta.no-types"))), UploadRejection.parse(body))
            assertEquals(words, upload.words)
        }
    }

    @Test
    fun `a text that deviates from the form parses to null`() {
        val error = "8:46: Unknown class type 'Bulbb' of object 'spare' (kmodel.unknown-class-type)"
        listOf(
            "",
            "Unknown class type 'Bulbb' for object 'spare'",
            "Upload of the model rejected: 1 error",
            "Upload of the model rejected: 1 error\n$error\n",
            "Upload of the model rejected: 1 error\r\n$error",
            "Upload of the model rejected: 1 errors\n$error",
            "Upload of the model rejected: 2 error\n$error\n$error",
            "Upload of the model rejected: 2 errors\n$error",
            "Upload of the model rejected: 0 errors",
            "Upload of the model rejected: 01 error\n$error",
            "Upload of the bridge rejected: 1 error\n$error",
            "Upload of the state machine for lamp rejected: 1 error\n$error",
            "Upload of the model rejected: 1 error\n\n",
            "Upload of the model rejected: 1 error\n0:46: Unknown class type 'Bulbb' of object 'spare' (kmodel.unknown-class-type)",
            "Upload of the model rejected: 1 error\n8:0: Unknown class type 'Bulbb' of object 'spare' (kmodel.unknown-class-type)",
            "Upload of the model rejected: 1 error\n08:46: Unknown class type 'Bulbb' of object 'spare' (kmodel.unknown-class-type)",
            "Upload of the model rejected: 1 error\n8:46: Unknown class type 'Bulbb' of object 'spare'",
            "Upload of the model rejected: 1 error\n8:46: (kmodel.unknown-class-type)",
            "Upload of the model rejected: 1 error\n8:46 Unknown class type 'Bulbb' of object 'spare' (kmodel.unknown-class-type)",
            "Upload of the model rejected: 1 error\n8:46: Unknown class type 'Bulbb' (kmodel unknown)",
            " Upload of the model rejected: 1 error\n$error",
            "Upload of the model rejected: 1 error\n$error ",
        ).forEach { body ->
            assertNull(UploadRejection.parse(body), body)
        }
    }

    @Test
    fun `the values refuse what the body cannot express`() {
        assertFailsWith<IllegalArgumentException> { UploadRejection(RejectedUpload.Model, emptyList()) }
        assertFailsWith<IllegalArgumentException> { UploadError(0, 1, "message", "kmodel.invalid-number") }
        assertFailsWith<IllegalArgumentException> { UploadError(1, 0, "message", "kmodel.invalid-number") }
        assertFailsWith<IllegalArgumentException> { UploadError(1, 1, "two\nlines", "kmodel.invalid-number") }
        assertFailsWith<IllegalArgumentException> { UploadError(1, 1, "", "kmodel.invalid-number") }
        assertFailsWith<IllegalArgumentException> { UploadError(1, 1, "message", "kmodel invalid") }
        assertFailsWith<IllegalArgumentException> { RejectedUpload.StateMachine("two\nlines") }
    }
}
