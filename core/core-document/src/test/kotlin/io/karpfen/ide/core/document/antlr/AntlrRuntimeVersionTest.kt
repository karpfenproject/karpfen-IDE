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
package io.karpfen.ide.core.document.antlr

import io.karpfen.dsl.grammar.kmeta.KmetaLexer
import io.karpfen.dsl.grammar.kmeta.KmetaParser
import io.karpfen.dsl.grammar.kmodel.KmodelLexer
import io.karpfen.dsl.grammar.kmodel.KmodelParser
import io.karpfen.dsl.grammar.kstates.KstatesLexer
import io.karpfen.dsl.grammar.kstates.KstatesParser
import org.antlr.v4.runtime.RuntimeMetaData
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ANTLR runtime that the IDE runs with is the one the parsers of dsl-tools were generated for. Every generated lexer
 * and parser checks the version of the runtime when its class is initialized. On a mismatch it writes a warning to
 * standard error, which would reach the IDE's console and the standard error of the command-line tool. The build
 * therefore takes the version of the runtime from dsl-tools alone. The test initializes the classes in a class loader
 * of their own, so the check runs however many tests loaded them before.
 */
class AntlrRuntimeVersionTest {

    @Test
    fun `the generated dsl-tools lexers and parsers find the runtime they were generated for`() {
        val locations = (GENERATED + RuntimeMetaData::class.java).map { it.protectionDomain.codeSource.location }.distinct()
        val standardError = ByteArrayOutputStream()
        val previous = System.err

        URLClassLoader(locations.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { isolated ->
            System.setErr(PrintStream(standardError, true, Charsets.UTF_8))
            try {
                GENERATED.forEach { Class.forName(it.name, true, isolated) }
            } finally {
                System.setErr(previous)
            }
        }

        assertEquals("", standardError.toString(Charsets.UTF_8), "the ANTLR runtime ${RuntimeMetaData.getRuntimeVersion()} on the classpath")
    }

    private companion object {
        /** The lexers and parsers that dsl-tools generates from its grammars. */
        val GENERATED: List<Class<*>> = listOf(
            KmetaLexer::class.java, KmetaParser::class.java,
            KmodelLexer::class.java, KmodelParser::class.java,
            KstatesLexer::class.java, KstatesParser::class.java,
        )
    }
}
