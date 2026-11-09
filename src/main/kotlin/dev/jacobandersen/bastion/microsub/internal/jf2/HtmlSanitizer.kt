package dev.jacobandersen.bastion.microsub.internal.jf2

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Sanitizes untrusted feed HTML for `content.html`. Relative links are made
 * absolute against the entry's URL, scripts, styles, iframes and event
 * handlers are dropped, and only http(s)/mailto links survive.
 */
object HtmlSanitizer {
    private val SAFELIST: Safelist =
        Safelist
            .relaxed()
            .addAttributes("img", "loading", "srcset")
            .addAttributes("a", "rel")
            .preserveRelativeLinks(false)

    private val OUTPUT = Document.OutputSettings().prettyPrint(false)

    fun sanitize(
        html: String,
        baseUri: String?,
    ): String = Jsoup.clean(html, baseUri.orEmpty(), SAFELIST, OUTPUT).trim()

    /**
     * Plain text of an HTML fragment. Whitespace collapses like it does when rendered, `<br>` becomes a
     * line break, a paragraph ends with a blank line and other blocks end with a line break, so adjacent
     * lines and paragraphs never fuse into one run of words. Whitespace inside `<pre>` is kept as is.
     */
    fun text(html: String): String {
        val out = StringBuilder()
        NodeTraversor.traverse(TextVisitor(out), Jsoup.parseBodyFragment(html).body())
        return out
            .toString()
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private class TextVisitor(
        private val out: StringBuilder,
    ) : NodeVisitor {
        override fun head(
            node: Node,
            depth: Int,
        ) {
            when {
                node is TextNode -> appendText(node)
                node is Element && node.nameIs("br") -> out.append('\n')
                node is Element && node.isBlock -> breakBefore(node)
            }
        }

        override fun tail(
            node: Node,
            depth: Int,
        ) {
            if (node is Element && node.isBlock) breakBefore(node)
        }

        private fun appendText(node: TextNode) {
            if (generateSequence(node.parent()) { it.parent() }.any { it.nameIs("pre") }) {
                out.append(node.wholeText)
                return
            }
            val collapsed = node.wholeText.replace(Regex("[ \\t\\n\\r\\f]+"), " ")
            val afterSpace = out.isEmpty() || out.last() == '\n' || out.last() == ' '
            out.append(if (afterSpace) collapsed.trimStart() else collapsed)
        }

        /** Ends the current line, or the paragraph with a blank line, unless nothing has been written yet. */
        private fun breakBefore(block: Element) {
            if (out.isEmpty()) return
            val wanted = if (block.nameIs("p")) 2 else 1
            val trailing = out.reversed().takeWhile { it == '\n' }.length
            repeat((wanted - trailing).coerceAtLeast(0)) { out.append('\n') }
        }
    }

    /** The `content` jf2 object for an html value, a plain-text value, or both. */
    fun content(
        html: String?,
        text: String?,
        baseUri: String?,
    ): Map<String, Any>? {
        val cleanHtml = html?.takeIf { it.isNotBlank() }?.let { sanitize(it, baseUri) }?.takeIf { it.isNotBlank() }
        val plain = text?.takeIf { it.isNotBlank() }?.trim() ?: cleanHtml?.let(::text)
        return jf2 {
            this["html"] = cleanHtml
            this["text"] = plain
        }.takeIf { it.isNotEmpty() }
    }
}
