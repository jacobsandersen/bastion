package dev.jacobandersen.bastion.microsub.feeds

/**
 * What Bastion knows about a followed feed beyond the Microsub spec's feed object: the document [format]
 * (`mf2`, `rss`, `atom` or `json`) and the WebSub subscription [websub] state (`active` or `pending`, null when the
 * feed has no subscription).
 */
data class FeedDetails(
    val url: String,
    val name: String?,
    val photo: String?,
    val description: String?,
    val format: String,
    val websub: String?,
)
