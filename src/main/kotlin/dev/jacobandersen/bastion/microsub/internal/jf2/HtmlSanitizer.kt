package dev.jacobandersen.bastion.microsub.internal.jf2

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

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

    fun text(html: String): String =
        Jsoup
            .parseBodyFragment(html)
            .body()
            .wholeText()
            .trim()
            .replace(Regex("[ \\t]+\\n"), "\n")

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
