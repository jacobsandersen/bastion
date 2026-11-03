package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.types.SyndicationFormat
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value

/**
 * Builds the downstream copy of a post for syndication targets. The copy is an
 * excerpt plus a permalink, never the full content. The text template comes from
 * the post type's configured syndication format.
 *
 * The [maxGraphemes] budget covers the total rendered text, counted in grapheme
 * clusters via [Graphemes] so emoji-heavy posts stay within limits like
 * Bluesky's 300. The permalink is never dropped or truncated; when the title
 * and link alone exhaust the budget the excerpt is omitted first.
 */
object SyndicationContentMapper {
    fun build(
        post: Post,
        canonicalUrl: String,
        maxGraphemes: Int,
        format: SyndicationFormat,
    ): Mf2Object {
        val rendered = SyndicationTextRenderer.render(post, canonicalUrl, maxGraphemes, format)

        var mapped = post.post.setProperty("content", Mf2Value.String(rendered.text))
        if (post.post.hasProperty("summary")) {
            mapped = mapped.setProperty("summary", Mf2Value.String(rendered.text))
        }
        return mapped.setProperty("url", Mf2Value.String(canonicalUrl))
    }
}
