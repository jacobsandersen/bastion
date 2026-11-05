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
 * canonical URL appears in its `in-reply-to`, matched on the exact URL first
 * and then on its page without the fragment. A canonical URL on another
 * origin than its source (such as a Bridgy proxy) is only a fallback key,
 * used when no source URL or same-origin canonical URL claims it. A nested response that names no
 * known parent falls back to the source that displays it. Nested responses
 * whose displaying source is not shown are hidden, and a reply cycle is broken
 * at its oldest member, which becomes a top-level response; responses that
 * only lead into a cycle keep their parent.
 */
object WebmentionThreadResolver {
    fun resolve(mentions: List<ReceivedWebmention>): List<ThreadedWebmention> {
        val byId = mentions.associateBy { it.id }
        val visible = mentions.filter { it.viaId == null || it.viaId in byId }.sortedBy { it.firstSeenAt }

        val urlOf = visible.associate { it.id to (it.url ?: it.rawMf2?.let(ReceivedWebmentionAnalyzer::canonicalUrl)) }
        val inReplyToOf =
            visible.associate { it.id to (it.inReplyTo ?: it.rawMf2?.let(ReceivedWebmentionAnalyzer::inReplyToOf).orEmpty()) }

        val byKey = mutableMapOf<String, ReceivedWebmention>()
        val byPage = mutableMapOf<String, ReceivedWebmention>()
        fun index(
            mention: ReceivedWebmention,
            url: String,
        ) {
            byKey.putIfAbsent(ReceivedWebmentionAnalyzer.urlKey(url), mention)
            if ('#' !in url) byPage.putIfAbsent(ReceivedWebmentionAnalyzer.normalizeUrl(url), mention)
        }
        visible.forEach { mention ->
            ReceivedWebmentionAnalyzer.identityUrls(mention.sourceUrl, urlOf[mention.id]).forEach { index(mention, it) }
        }
        visible.forEach { mention ->
            urlOf[mention.id]
                ?.takeUnless { ReceivedWebmentionAnalyzer.sameOrigin(mention.sourceUrl, it) }
                ?.let { index(mention, it) }
        }

        val parentOf = mutableMapOf<UUID, ReceivedWebmention?>()
        visible.forEach { mention ->
            val explicit =
                inReplyToOf
                    .getValue(mention.id)
                    .firstNotNullOfOrNull { url ->
                        (byKey[ReceivedWebmentionAnalyzer.urlKey(url)] ?: byPage[ReceivedWebmentionAnalyzer.normalizeUrl(url)])
                            ?.takeIf { it.id != mention.id }
                    }
            parentOf[mention.id] = explicit ?: mention.viaId?.let(byId::get)
        }

        breakCycles(visible, parentOf)

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

    /**
     * Each response has at most one parent, so every cycle is a simple loop.
     * Walk each unvisited chain; when it runs back into the current walk, the
     * nodes from that point on form a cycle, and only its oldest member is
     * detached. Responses that merely lead into a cycle keep their parent.
     */
    private fun breakCycles(
        visible: List<ReceivedWebmention>,
        parentOf: MutableMap<UUID, ReceivedWebmention?>,
    ) {
        val done = mutableSetOf<UUID>()
        visible.forEach { start ->
            val path = mutableListOf<ReceivedWebmention>()
            val onPath = mutableSetOf<UUID>()
            var current: ReceivedWebmention? = start
            while (current != null && current.id !in done && onPath.add(current.id)) {
                path += current
                current = parentOf[current.id]
            }
            val entry = current
            if (entry != null && entry.id in onPath) {
                val cycle = path.subList(path.indexOfFirst { it.id == entry.id }, path.size)
                val oldest = cycle.minWith(compareBy<ReceivedWebmention> { it.firstSeenAt }.thenBy { it.id })
                parentOf[oldest.id] = null
            }
            path.forEach { done += it.id }
        }
    }
}
