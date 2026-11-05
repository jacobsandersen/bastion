package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmention
import java.util.UUID

/** A displayable received webmention with its resolved place in the reply thread. */
data class ThreadedWebmention(
    val mention: ReceivedWebmention,
    val url: String?,
    val inReplyTo: List<String>,
    val parent: ReceivedWebmention?,
    val via: ReceivedWebmention?,
)

/**
 * Threads one post's verified webmentions. This happens on read, so replies
 * that arrive before their parent still thread once the parent is known.
 *
 * A response's parent is the first other response whose source URL or
 * canonical URL appears in its `in-reply-to`. A nested response that names no
 * known parent falls back to the source that displays it. Nested responses
 * whose displaying source is not shown are hidden, and a reply cycle is broken
 * at its oldest member, which becomes a top-level response.
 */
object WebmentionThreadResolver {
    fun resolve(mentions: List<ReceivedWebmention>): List<ThreadedWebmention> {
        val byId = mentions.associateBy { it.id }
        val visible = mentions.filter { it.viaId == null || it.viaId in byId }.sortedBy { it.firstSeenAt }

        val urlOf = visible.associate { it.id to (it.url ?: it.rawMf2?.let(ReceivedWebmentionAnalyzer::canonicalUrl)) }
        val inReplyToOf =
            visible.associate { it.id to (it.inReplyTo ?: it.rawMf2?.let(ReceivedWebmentionAnalyzer::inReplyToOf).orEmpty()) }

        val byKey = mutableMapOf<String, ReceivedWebmention>()
        visible.forEach { mention ->
            listOfNotNull(mention.sourceUrl, urlOf[mention.id])
                .map(ReceivedWebmentionAnalyzer::normalizeUrl)
                .forEach { key -> byKey.putIfAbsent(key, mention) }
        }

        val parentOf = mutableMapOf<UUID, ReceivedWebmention?>()
        visible.forEach { mention ->
            val explicit =
                inReplyToOf
                    .getValue(mention.id)
                    .firstNotNullOfOrNull { url ->
                        byKey[ReceivedWebmentionAnalyzer.normalizeUrl(url)]?.takeIf { it.id != mention.id }
                    }
            parentOf[mention.id] = explicit ?: mention.viaId?.let(byId::get)
        }

        visible.forEach { mention ->
            val seen = mutableSetOf(mention.id)
            var ancestor = parentOf[mention.id]
            while (ancestor != null) {
                if (!seen.add(ancestor.id)) {
                    parentOf[mention.id] = null
                    break
                }
                ancestor = parentOf[ancestor.id]
            }
        }

        return visible.map { mention ->
            ThreadedWebmention(
                mention = mention,
                url = urlOf[mention.id],
                inReplyTo = inReplyToOf.getValue(mention.id),
                parent = parentOf[mention.id],
                via = mention.viaId?.let(byId::get),
            )
        }
    }
}
