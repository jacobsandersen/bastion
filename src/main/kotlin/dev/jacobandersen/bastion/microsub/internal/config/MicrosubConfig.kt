package dev.jacobandersen.bastion.microsub.internal.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * @property pollInterval How often a feed without an active WebSub subscription is fetched.
 * @property websubFallbackInterval How often a feed with an active WebSub subscription is still polled.
 * @property maxBackoff Upper bound for the failure backoff between fetches.
 * @property schedulerInterval How often the poll job looks for due feeds.
 * @property pollBatchSize The most feeds one poll run claims.
 * @property refreshStaleAfter A client-requested refresh only fetches feeds last fetched longer ago than this.
 * @property claimTimeout How long a claimed fetch may run before another run may take it over.
 * @property connectTimeout Outbound connect timeout for feed fetches.
 * @property readTimeout Outbound read timeout for feed fetches.
 * @property maxBodyBytes Feed documents larger than this are rejected.
 * @property maxItemsPerFetch The most entries taken from one feed document.
 * @property backfillAsRead Whether the entries ingested when following a feed start out read.
 * @property retention Read or removed entries not seen in their feed for this long are deleted.
 * @property defaultLimit Timeline page size when the client gives none.
 * @property maxLimit Largest timeline page served.
 */
@ConfigurationProperties(prefix = "bastion.microsub")
data class MicrosubConfig(
    val pollInterval: Duration = Duration.ofMinutes(30),
    val websubFallbackInterval: Duration = Duration.ofHours(12),
    val maxBackoff: Duration = Duration.ofDays(1),
    val schedulerInterval: Duration = Duration.ofMinutes(5),
    val pollBatchSize: Int = 50,
    val refreshStaleAfter: Duration = Duration.ofMinutes(5),
    val claimTimeout: Duration = Duration.ofMinutes(5),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(15),
    val maxBodyBytes: Int = 5 * 1024 * 1024,
    val maxItemsPerFetch: Int = 50,
    val backfillAsRead: Boolean = true,
    val retention: Duration = Duration.ofDays(30),
    val defaultLimit: Int = 20,
    val maxLimit: Int = 100,
)
