package dev.jacobandersen.bastion.websub.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * WebSub configuration for both roles Bastion plays.
 *
 * As a publisher, when a post is created, updated, deleted or undeleted
 * Bastion pings each configured hub with `hub.mode=publish` and the topic
 * URL, so hubs re-fetch the feed.
 *
 * As a subscriber (used by Microsub to follow feeds), Bastion subscribes to
 * hubs with a callback under `bastion.public-url` and verifies signed content
 * deliveries; see [SubscriberConfig].
 *
 * @property hubs The hub URLs to notify. An empty list disables publishing.
 * @property topicUrl The topic (feed) URL a hub re-fetches after a publish
 *   ping. Bastion is API-only and does not itself serve a feed document, so
 *   this is an operator-supplied URL that some external system renders as the
 *   feed. A blank value disables publishing.
 * @property connectTimeoutSeconds Outbound connect timeout for hub requests.
 * @property readTimeoutSeconds Outbound read timeout for hub requests.
 */
@ConfigurationProperties(prefix = "bastion.websub")
data class WebsubConfig(
    val hubs: List<String> = emptyList(),
    val topicUrl: String = "",
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 10,
    val subscriber: SubscriberConfig = SubscriberConfig(),
) {
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
