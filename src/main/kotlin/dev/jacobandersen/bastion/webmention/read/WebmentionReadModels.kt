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
