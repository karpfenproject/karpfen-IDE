package io.karpfen.ide.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/** A dotted name used in code, e.g. `javax.swing.JFrame` in `val frame = javax.swing.JFrame()`, and its 1-based line. */
internal data class QualifiedReference(val name: String, val line: Int) {
    override fun toString(): String = "$name (line $line)"
}

/**
 * Finds fully qualified references in Kotlin source text.
 *
 * Import rules alone miss references such as `java.awt.Toolkit.getDefaultToolkit()` or `javax.swing.JPanel()`, which
 * compile without an import because the JDK is on every classpath. This scanner reports every dotted name in the code
 * outside `package`/`import` directives, comments, string and character literals and backtick identifiers, so the
 * layer rules can check them against the same forbidden package prefixes as the imports.
 * Limitations (deliberately simple): expressions inside string templates and names split across lines are not seen.
 */
internal object SourceReferences {

    private val dottedName = Regex("""(?<![\w.$])[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+""")
    private val directive = Regex("""^\s*(?:package|import)\s""")

    /** Dotted names used in the code of [source] (see the object documentation for what is skipped). */
    fun qualifiedReferences(source: String): List<QualifiedReference> =
        blankCommentsAndLiterals(source).lines().flatMapIndexed { index, line ->
            if (directive.containsMatchIn(line)) {
                emptyList()
            } else {
                dottedName.findAll(line).map { QualifiedReference(it.value, index + 1) }.toList()
            }
        }

    /** [source] with comments, string/character literals and backtick identifiers replaced by spaces (newlines kept). */
    fun blankCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            val end = when {
                source.startsWith("//", i) -> source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> endOfBlockComment(source, i)
                source.startsWith("\"\"\"", i) -> endOfRawString(source, i)
                source[i] == '"' || source[i] == '\'' || source[i] == '`' -> endOfQuoted(source, i)
                else -> {
                    i++
                    continue
                }
            }
            for (k in i until end) {
                if (out[k] != '\n') out.setCharAt(k, ' ')
            }
            i = end
        }
        return out.toString()
    }

    /** Kotlin block comments nest. */
    private fun endOfBlockComment(source: String, start: Int): Int {
        var depth = 0
        var j = start
        while (j < source.length) {
            when {
                source.startsWith("/*", j) -> {
                    depth++
                    j += 2
                }
                source.startsWith("*/", j) -> {
                    depth--
                    j += 2
                    if (depth == 0) return j
                }
                else -> j++
            }
        }
        return source.length
    }

    /** A raw string ends at the first `"""`; further quotes directly after it still belong to the string. */
    private fun endOfRawString(source: String, start: Int): Int {
        val close = source.indexOf("\"\"\"", start + 3)
        if (close < 0) return source.length
        var j = close + 3
        while (j < source.length && source[j] == '"') j++
        return j
    }

    /** A single-line literal or backtick identifier delimited by the character at [start]; `\` escapes. */
    private fun endOfQuoted(source: String, start: Int): Int {
        val quote = source[start]
        var j = start + 1
        while (j < source.length && source[j] != quote && source[j] != '\n') {
            j += if (source[j] == '\\') 2 else 1
        }
        return minOf(source.length, j + 1)
    }
}

/** Fully qualified references in this file's code (see [SourceReferences]). */
internal val KoFileDeclaration.qualifiedReferences: List<QualifiedReference>
    get() = SourceReferences.qualifiedReferences(text)
