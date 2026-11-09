package dev.jacobandersen.bastion.microsub.internal.fetch

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * Normalizes Atom `type="xhtml"` content (RFC 4287 section 3.1.1.3) to plain HTML.
 *
 * Rome returns the XHTML div as serialized XML, so elements keep whatever
 * namespace prefix the feed used (`<default:br/>`). An HTML sanitizer does not
 * know prefixed names and would drop every one of them. Elements in the XHTML
 * namespace, or in no namespace at all, are renamed to their local name; elements
 * from other namespaces keep their qualified name and are left to the sanitizer.
 * The wrapping `div` is not part of the content and is unwrapped.
 */
internal object XhtmlContent {
    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"

    fun normalize(xml: String): String {
        val source = Jsoup.parse(xml, "", Parser.xmlParser())
        val target = Document.createShell("")
        target.outputSettings().prettyPrint(false)
        val body = target.body()
        copyChildren(unwrap(source), body)
        return body.html()
    }

    private fun unwrap(source: Document): Element {
        val content = source.children().singleOrNull() ?: return source
        val onlyChild = source.childNodes().none { it !== content && !isBlank(it) }
        return if (onlyChild && localName(content) == "div" && namespace(content).let { it == null || it == XHTML_NS }) content else source
    }

    private fun isBlank(node: Node) = (node is TextNode && node.isBlank) || node is Comment

    private fun copyChildren(
        from: Element,
        to: Element,
    ) {
        from.childNodes().forEach { child ->
            when (child) {
                is TextNode -> {
                    to.appendText(child.wholeText)
                }

                is Element -> {
                    val copy = Element(if (isHtml(child)) localName(child) else child.tagName())
                    child.attributes().filterNot { isNamespaceDeclaration(it.key) }.forEach { copy.attr(it.key, it.value) }
                    to.appendChild(copy)
                    copyChildren(child, copy)
                }

                is DataNode -> {
                    to.appendText(child.wholeData)
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun isHtml(element: Element) = namespace(element).let { it == null || it == XHTML_NS }

    private fun isNamespaceDeclaration(name: String) = name == "xmlns" || name.startsWith("xmlns:")

    private fun localName(element: Element) = element.tagName().substringAfter(':')

    /** The namespace the element's prefix (or the default namespace) is bound to, or null when undeclared. */
    private fun namespace(element: Element): String? {
        val prefix = element.tagName().substringBefore(':', "")
        val declaration = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
        var current: Element? = element
        while (current != null) {
            if (current.hasAttr(declaration)) return current.attr(declaration)
            current = current.parent()
        }
        return null
    }
}
