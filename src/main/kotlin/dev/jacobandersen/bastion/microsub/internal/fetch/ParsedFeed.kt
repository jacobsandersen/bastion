package dev.jacobandersen.bastion.microsub.internal.fetch

import dev.jacobandersen.bastion.microsub.internal.jf2.Jf2Item

enum class FeedFormat {
    MF2,
    XML,
    JSON,
}

/** A feed document normalized to jf2, whichever format it came in. */
data class ParsedFeed(
    val format: FeedFormat,
    val url: String,
    val name: String?,
    val photo: String?,
    val description: String?,
    val items: List<Jf2Item>,
    val hubs: List<String>,
    val self: String?,
)
