package dev.jacobandersen.bastion.websub.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * WebSub configuration for both roles Bastion plays.
 *
 * As a publisher, when a post is created, updated, deleted or undeleted
 * Bastion pings each configured hub with `hub.mode=publish` and the URL of
 * every feed the post belongs to (see [Topics]), so hubs re-fetch those feeds.
 * Bastion does not serve or format feeds itself; the topic URLs are whatever
 * the external frontend serves, in any format.
 *
 * As a subscriber (used by Microsub to follow feeds), Bastion subscribes to
 * hubs with a callback under `bastion.public-url` and verifies signed content
 * deliveries; see [SubscriberConfig].
 *
 * @property hubs The hub URLs to notify. An empty list disables publishing.
 * @property topics URL templates for the feeds a post can appear in. See [Topics].
 * @property connectTimeoutSeconds Outbound connect timeout for hub requests.
 * @property readTimeoutSeconds Outbound read timeout for hub requests.
 */
@ConfigurationProperties(prefix = "bastion.websub")
data class WebsubConfig(
    val hubs: List<String> = emptyList(),
    val topics: Topics = Topics(),
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 10,
    val subscriber: SubscriberConfig = SubscriberConfig(),
) {
    /**
     * URLs of the feeds served by the frontend. Every property is optional and
     * a blank one disables that kind of feed; with all blank, publishing is
     * disabled. Placeholder values are URL-encoded like `encodeURIComponent`.
     *
     * @property main The main feed. Pinged for every feed-visible change.
     * @property uncategorized The feed of posts without tags.
     * @property type Template with `{type}` for per-post-type feeds.
     * @property types The post types that have a feed, as `type=name` entries where `name` is what
     *   the frontend uses for `{type}` (e.g. `article=articles,note=notes`). A bare `type` uses its own
     *   name. Empty means every type, under its own name.
     * @property tag Template with `{tag}` for per-tag feeds (the tag as written, no `#`).
     * @property year Template with `{year}` for per-year feeds. Only the current
     *   year is pinged; earlier years are archives.
     */
    data class Topics(
        val main: String = "",
        val uncategorized: String = "",
        val type: String = "",
        val types: List<String> = emptyList(),
        val tag: String = "",
        val year: String = "",
    ) {
        /** [types] parsed into post type to feed name. */
        val feedNames: Map<String, String> =
            types
                .map(String::trim)
                .filter(String::isNotEmpty)
                .associate { entry ->
                    val type = entry.substringBefore('=').trim()
                    require(type.isNotEmpty()) { "bastion.websub.topics.types has an entry without a type: '$entry'" }
                    type to entry.substringAfter('=', "").trim().ifEmpty { type }
                }

        init {
            require(type.isBlank() || "{type}" in type) { "bastion.websub.topics.type must contain {type}" }
            require(tag.isBlank() || "{tag}" in tag) { "bastion.websub.topics.tag must contain {tag}" }
            require(year.isBlank() || "{year}" in year) { "bastion.websub.topics.year must contain {year}" }
        }
    }

    /**
     * @property leaseSeconds The lease Bastion requests; hubs may grant a different one.
     * @property renewBefore Active subscriptions whose lease ends within this window are renewed.
     * @property renewalInterval How often the renewal job runs.
     * @property pendingRetryAfter A subscription still unverified after this long is requested again.
     * @property maxContentBytes Content deliveries larger than this are ignored.
     */
    data class SubscriberConfig(
        val leaseSeconds: Long = 864_000,
        val renewBefore: Duration = Duration.ofDays(1),
        val renewalInterval: Duration = Duration.ofHours(1),
        val pendingRetryAfter: Duration = Duration.ofDays(1),
        val maxContentBytes: Int = 10 * 1024 * 1024,
    )
}
