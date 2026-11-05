package dev.jacobandersen.bastion.webmention.read

import java.time.Instant
import java.util.UUID

data class WebmentionView(
    val sourceUrl: String,
    val targetUrl: String,
    val interaction: String,
    val authorName: String?,
    val authorUrl: String?,
    val authorPhoto: String?,
    val contentText: List<String>,
    val contentHtml: List<String>,
    val firstSeenAt: Instant,
    val verifiedAt: Instant,
    /** The response's canonical `u-url`, when the source declares one; it may differ from [sourceUrl]. */
    val url: String?,
    /** Whether [url] shares an origin with [sourceUrl]; a cross-origin [url] is only the source's claim. */
    val urlSameOrigin: Boolean,
    /** The URLs this response replies to. */
    val inReplyTo: List<String>,
    /** The [sourceUrl] of the response this one replies to on the same post; null for top-level responses. */
    val parentSourceUrl: String?,
    /** For a response learned from another source's page (Salmention), that source's [sourceUrl]. */
    val viaSourceUrl: String?,
)

data class PostWebmentionsView(
    val counts: WebmentionCountsView,
    val webmentions: List<WebmentionView>,
)

data class WebmentionCountsView(
    val total: Int,
    val reply: Int,
    val like: Int,
    val repost: Int,
    val bookmark: Int,
    val rsvp: Int,
    val mention: Int,
) {
    companion object {
        val EMPTY = WebmentionCountsView(0, 0, 0, 0, 0, 0, 0)

        internal fun of(counts: Map<String, Int>): WebmentionCountsView =
            WebmentionCountsView(
                total = counts.values.sum(),
                reply = counts["REPLY"] ?: 0,
                like = counts["LIKE"] ?: 0,
                repost = counts["REPOST"] ?: 0,
                bookmark = counts["BOOKMARK"] ?: 0,
                rsvp = counts["RSVP"] ?: 0,
                mention = counts["MENTION"] ?: 0,
            )
    }
}
