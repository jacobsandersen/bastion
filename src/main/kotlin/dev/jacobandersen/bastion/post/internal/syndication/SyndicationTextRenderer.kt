package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.types.ExcerptConfig
import dev.jacobandersen.bastion.post.internal.types.ExcerptTruncation
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormat
import dev.jacobandersen.bastion.post.internal.types.SyndicationTemplate
import dev.jacobandersen.bastion.post.internal.types.TemplateField
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.firstHtml
import dev.jacobandersen.microformats2.firstText
import dev.jacobandersen.microformats2.texts
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * The rendered downstream text plus the mf2 properties it displays, so an update can replace them with the same
 * values the text was built from. Properties the renderer overwrites itself (content/summary/url) are not special
 * cased here; callers decide how to merge.
 */
data class RenderedSyndicationText(
    val text: String,
    val usedProperties: Map<String, String>,
)

object SyndicationTextRenderer {
    fun render(
        post: Post,
        canonicalUrl: String,
        maxGraphemes: Int,
        format: SyndicationFormat,
    ): RenderedSyndicationText {
        val max = maxGraphemes.coerceAtLeast(1)
        val fields = PostFields(post.post)
        val excerpt = format.excerpt
        val template = chooseTemplate(format, fields)

        val body =
            excerpt.sources
                .asSequence()
                .filter { it !in template.propertyKeys }
                .firstNotNullOfOrNull(fields::text)
        if (body == null) return overflow(format, fields, canonicalUrl, max)

        val fixed = template.render("", canonicalUrl, fields::resolve)
        val allowance = max - Graphemes.count(fixed)
        if (allowance < excerpt.minGraphemes.coerceAtLeast(1)) return overflow(format, fields, canonicalUrl, max)

        return RenderedSyndicationText(
            text = template.render(truncate(body, allowance, excerpt), canonicalUrl, fields::resolve),
            usedProperties = fields.used(template),
        )
    }

    private fun chooseTemplate(
        format: SyndicationFormat,
        fields: PostFields,
    ): SyndicationTemplate =
        if (format.format.fields.any { fields.resolve(it) == null }) {
            requireNotNull(format.fallback) { "format uses optional placeholders but has no fallback" }
        } else {
            format.format
        }

    private fun overflow(
        format: SyndicationFormat,
        fields: PostFields,
        canonicalUrl: String,
        maxGraphemes: Int,
    ): RenderedSyndicationText {
        val template = format.overflow
        if (template.fields.any { fields.resolve(it) == null }) {
            return RenderedSyndicationText(canonicalUrl, emptyMap())
        }

        val overflowText = template.render(null, canonicalUrl, fields::resolve)
        if (Graphemes.count(overflowText) <= maxGraphemes) {
            return RenderedSyndicationText(overflowText, fields.used(template))
        }

        val name = fields.text("name")
        if (name != null && template.usesName) {
            val fixed =
                template.render(null, canonicalUrl) { field ->
                    if (field == NAME_FIELD) "" else fields.resolve(field)
                }
            val allowance = maxGraphemes - Graphemes.count(fixed)
            if (allowance >= MIN_TITLE_GRAPHEMES) {
                val truncated = truncate(name, allowance, format.excerpt)
                return RenderedSyndicationText(
                    text =
                        template.render(null, canonicalUrl) { field ->
                            if (field == NAME_FIELD) truncated else fields.resolve(field)
                        },
                    usedProperties = fields.used(template),
                )
            }
        }

        return RenderedSyndicationText(canonicalUrl, emptyMap())
    }

    private fun truncate(
        text: String,
        allowance: Int,
        config: ExcerptConfig,
    ): String {
        if (allowance <= 0) return ""
        if (Graphemes.count(text) <= allowance) return text
        val roomForText = (allowance - Graphemes.count(config.ellipsis)).coerceAtLeast(0)
        val cut = Graphemes.take(text, roomForText).trimEnd()
        val head =
            if (config.truncate == ExcerptTruncation.WORD) {
                val lastSpace = cut.lastIndexOf(' ')
                if (lastSpace >= 0 && Graphemes.count(cut.substring(0, lastSpace)) >= MIN_WORD_KEEP_GRAPHEMES) {
                    cut.substring(0, lastSpace).trimEnd()
                } else {
                    cut
                }
            } else {
                cut
            }
        return head + config.ellipsis
    }

    private val NAME_FIELD = TemplateField.Property("name")
    private const val MIN_TITLE_GRAPHEMES = 10
    private const val MIN_WORD_KEEP_GRAPHEMES = 20
}

/** Reads normalized plain-text values for template fields and excerpt sources from a post's mf2 object. */
private class PostFields(
    private val post: Mf2Object,
) {
    /** The first text value of [key], falling back to stripped HTML when only an html value is present. */
    fun text(key: String): String? =
        (post.firstText(key) ?: post.firstHtml(key)?.let(::htmlToText))?.let(::normalize)?.takeIf { it.isNotBlank() }

    fun resolve(field: TemplateField): String? =
        when (field) {
            is TemplateField.Property -> text(field.key)
            TemplateField.Tags -> tags()
        }

    /** Values of the properties [template] shows, keyed by mf2 property. */
    fun used(template: SyndicationTemplate): Map<String, String> =
        template.propertyKeys.mapNotNull { key -> text(key)?.let { key to it } }.toMap()

    private fun tags(): String? =
        post
            .texts("category")
            .map { "#" + it.trim().removePrefix("#").replace(WHITESPACE, "") }
            .filter { it.length > 1 }
            .distinct()
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }

    private fun htmlToText(html: String): String {
        val document = Jsoup.parseBodyFragment(html)
        document.select("br, p, div, li, blockquote, pre, h1, h2, h3, h4, h5, h6, tr").forEach { it.after(TextNode(" ")) }
        return document.body().text()
    }

    private fun normalize(text: String): String = text.replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
