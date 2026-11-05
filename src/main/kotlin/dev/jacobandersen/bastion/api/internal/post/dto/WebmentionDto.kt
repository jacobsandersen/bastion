package dev.jacobandersen.bastion.api.internal.post.dto

import dev.jacobandersen.bastion.webmention.read.WebmentionView

data class WebmentionDto(
    val sourceUrl: String,
    val targetUrl: String,
    val interaction: String,
    val authorName: String?,
    val authorUrl: String?,
    val authorPhoto: String?,
    val contentText: List<String>,
    val contentHtml: List<String>,
    val firstSeenAt: String,
    val verifiedAt: String,
    val url: String?,
    val urlSameOrigin: Boolean,
    val inReplyTo: List<String>,
    val parentSourceUrl: String?,
    val viaSourceUrl: String?,
) {
    companion object {
        fun from(view: WebmentionView): WebmentionDto =
            WebmentionDto(
                sourceUrl = view.sourceUrl,
                targetUrl = view.targetUrl,
                interaction = view.interaction,
                authorName = view.authorName,
                authorUrl = view.authorUrl,
                authorPhoto = view.authorPhoto,
                contentText = view.contentText,
                contentHtml = view.contentHtml,
                firstSeenAt = view.firstSeenAt.toString(),
                verifiedAt = view.verifiedAt.toString(),
                url = view.url,
                urlSameOrigin = view.urlSameOrigin,
                inReplyTo = view.inReplyTo,
                parentSourceUrl = view.parentSourceUrl,
                viaSourceUrl = view.viaSourceUrl,
            )
    }
}
