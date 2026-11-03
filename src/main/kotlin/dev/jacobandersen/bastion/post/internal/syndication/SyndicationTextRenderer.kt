package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormat
import dev.jacobandersen.bastion.post.internal.types.SyndicationTemplate
import dev.jacobandersen.microformats2.firstText

data class RenderedSyndicationText(
    val text: String,
    val usedName: String?,
)

/**
 * Renders the downstream text for a post using its configured syndication
 * format. The permalink is never truncated; the excerpt is the only part that
 * is shortened to fit the target budget.
 */
object SyndicationTextRenderer {
    fun render(
        post: Post,
        canonicalUrl: String,
        maxGraphemes: Int,
        format: SyndicationFormat,
    ): RenderedSyndicationText {
        val max = maxGraphemes.coerceAtLeast(1)
        val name = normalizedOrNull(post.post.firstText("name"))
        val content = normalizedOrNull(post.post.firstText("content"))
        val template = chooseTemplate(format, name)

        val body =
            when {
                content != null -> content
                name != null && !template.usesName -> name
                else -> null
            }
        if (body == null) return overflow(format, name, canonicalUrl, max)

        val fixed = template.render(name, "", canonicalUrl)
        val allowance = max - Graphemes.count(fixed)
        if (allowance < MIN_EXCERPT_GRAPHEMES) return overflow(format, name, canonicalUrl, max)

        return RenderedSyndicationText(
            text = template.render(name, truncate(body, allowance), canonicalUrl),
            usedName = if (template.usesName) name else null,
        )
    }

    private fun chooseTemplate(
        format: SyndicationFormat,
        name: String?,
    ): SyndicationTemplate =
        if (format.format.usesName && name == null) {
            requireNotNull(format.fallback) { "format uses {name} but has no fallback" }
        } else {
            format.format
        }

    private fun overflow(
        format: SyndicationFormat,
        name: String?,
        canonicalUrl: String,
        maxGraphemes: Int,
    ): RenderedSyndicationText {
        if (format.overflow.usesName && name == null) {
            return RenderedSyndicationText(canonicalUrl, null)
        }

        val overflowText = format.overflow.render(name, null, canonicalUrl)
        if (Graphemes.count(overflowText) <= maxGraphemes) {
            return RenderedSyndicationText(overflowText, if (format.overflow.usesName) name else null)
        }

        if (name != null && format.overflow.usesName) {
            val fixed = format.overflow.render("", null, canonicalUrl)
            val allowance = maxGraphemes - Graphemes.count(fixed)
            if (allowance >= MIN_TITLE_GRAPHEMES) {
                return RenderedSyndicationText(
                    text = format.overflow.render(truncate(name, allowance), null, canonicalUrl),
                    usedName = name,
                )
            }
        }

        return RenderedSyndicationText(canonicalUrl, null)
    }

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun normalizedOrNull(text: String?): String? = text?.let(::normalize)?.takeIf { it.isNotBlank() }

    private fun truncate(
        text: String,
        allowance: Int,
    ): String {
        if (allowance <= 0) return ""
        if (Graphemes.count(text) <= allowance) return text
        val roomForText = (allowance - 1).coerceAtLeast(0)
        val cut = Graphemes.take(text, roomForText).trimEnd()
        val lastSpace = cut.lastIndexOf(' ')
        val head =
            if (lastSpace >= 0 && Graphemes.count(cut.substring(0, lastSpace)) >= MIN_WORD_KEEP_GRAPHEMES) {
                cut.substring(0, lastSpace).trimEnd()
            } else {
                cut
            }
        return head + "…"
    }

    private const val MIN_EXCERPT_GRAPHEMES = 20
    private const val MIN_TITLE_GRAPHEMES = 10
    private const val MIN_WORD_KEEP_GRAPHEMES = 20
}
