package dev.jacobandersen.bastion.webmention.internal

import dev.jacobandersen.bastion.post.query.PostStateView
import dev.jacobandersen.bastion.url.ContentUrlMatcher
import dev.jacobandersen.bastion.url.UrlExtractor
import dev.jacobandersen.bastion.util.HttpUtil
import dev.jacobandersen.bastion.webmention.internal.config.WebmentionConfig
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.service.EndpointCacheResult
import dev.jacobandersen.bastion.webmention.internal.data.service.WebmentionEndpointCacheService
import dev.jacobandersen.bastion.webmention.internal.data.service.WebmentionNotificationService
import dev.jacobandersen.bastion.webmention.internal.http.EndpointDiscovery
import dev.jacobandersen.bastion.webmention.internal.http.SendWebmentionResult
import dev.jacobandersen.bastion.webmention.internal.http.WebmentionHttpClient
import dev.jacobandersen.bastion.webmention.internal.util.Mf2TextExtractor
import dev.jacobandersen.bastion.webmention.internal.util.WebmentionUtil
import dev.jacobandersen.bastion.webmention.policy.OutboundWebmentionPolicy
import dev.jacobandersen.microformats2.Mf2Object
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class WebmentionService(
    private val jobScheduler: JobScheduler,
    private val notificationService: WebmentionNotificationService,
    private val endpointCacheService: WebmentionEndpointCacheService,
    private val httpClient: WebmentionHttpClient,
    private val config: WebmentionConfig,
    private val contentUrlMatcher: ContentUrlMatcher,
    private val policies: List<OutboundWebmentionPolicy> = emptyList(),
) {
    /**
     * Reconciles the outbound webmention notification state for a post against
     * its current URL and linked targets. This is idempotent and does not rely
     * on which event produced the call, so duplicate or out-of-order events
     * converge on the latest post state.
     *
     * [refresh] re-sends webmentions to targets that already have a delivered
     * notification, which is how targets learn that the source changed. It is
     * false for deletions, where targets are only retracted.
     */
    fun reconcile(
        post: PostStateView,
        refresh: Boolean,
    ) {
        val currentUrl = post.url
        val isReachable = post.publiclyReachable
        val linkedTargets = if (isReachable) targetUrlsOf(post.post) else emptySet()
        val currentTargets = linkedTargets.filterNot(::isSuppressed).toSet()
        val retractDelivered = isReachable || post.deleted

        val existing =
            (
                notificationService.notificationsByPostId(post.id) +
                    notificationService.notificationsBySourceUrl(currentUrl)
            ).distinctBy { it.id }

        existing.forEach { notification ->
            val sourceMatches = notification.sourceUrl == currentUrl
            val targetStillLinked = notification.targetUrl in linkedTargets
            val remainsDesired = isReachable && sourceMatches && targetStillLinked

            if (isSuppressed(notification.targetUrl)) {
                // Never contact a suppressed target, not even to retract. Something already delivered stays as it is.
                if (!(remainsDesired && notification.delivered)) {
                    notificationService.markInactiveSilent(notification.sourceUrl, notification.targetUrl)
                }
                return@forEach
            }

            if (!remainsDesired) {
                if (retractDelivered && notification.delivered) {
                    notificationService.markInactivePendingRetraction(notification.sourceUrl, notification.targetUrl)
                    enqueueSend(notification.sourceUrl, notification.targetUrl, forceRediscovery = true)
                } else {
                    notificationService.markInactiveSilent(notification.sourceUrl, notification.targetUrl)
                }
                return@forEach
            }

            when {
                notification.state != WebmentionState.ACTIVE -> {
                    notificationService.setActivePending(notification.sourceUrl, notification.targetUrl, post.id)
                    enqueueSend(notification.sourceUrl, notification.targetUrl)
                }

                refresh && notification.delivered -> {
                    notificationService.setActivePending(notification.sourceUrl, notification.targetUrl, post.id)
                    enqueueSend(notification.sourceUrl, notification.targetUrl, forceRediscovery = true)
                }
            }
        }

        if (!isReachable) return

        val existingKeys = existing.map { it.sourceUrl to it.targetUrl }.toSet()
        currentTargets
            .filterNot { target -> (currentUrl to target) in existingKeys }
            .forEach { target ->
                notificationService.setActivePending(currentUrl, target, post.id)
                enqueueSend(currentUrl, target)
            }
    }

    fun retryDueWebmentions() {
        val states = listOf(WebmentionState.ACTIVE, WebmentionState.INACTIVE)
        notificationService.dueForRetry(Instant.now(), states).forEach { notification ->
            enqueueSend(notification.sourceUrl, notification.targetUrl)
        }
    }

    fun sendWebmention(
        sourceUrl: String,
        targetUrl: String,
        forceRediscovery: Boolean = false,
    ) {
        logger.info { "Sending webmention for $sourceUrl to $targetUrl..." }

        if (contentUrlMatcher.isOwnContentUrl(targetUrl)) {
            logger.info { "Skipping self-webmention to $targetUrl" }
            recordTerminalFailure(sourceUrl, targetUrl, "self webmention skipped")
            return
        }

        if (isSuppressed(targetUrl)) {
            logger.info { "Skipping webmention to suppressed target $targetUrl" }
            notificationService.scheduleNextAttempt(sourceUrl, targetUrl, null)
            return
        }

        if (HttpUtil.isBlockedHost(targetUrl, failClosedOnDnsError = false)) {
            logger.info { "Skipping webmention to blocked target $targetUrl" }
            recordTerminalFailure(sourceUrl, targetUrl, "target URL resolves to a blocked address")
            return
        }

        val endpointUrl = resolveEndpointForTarget(targetUrl, forceRediscovery)
        if (endpointUrl == null) {
            logger.info { "No remote webmention endpoint found for $targetUrl" }
            recordTerminalFailure(sourceUrl, targetUrl, "no webmention endpoint advertised")
            return
        }

        if (HttpUtil.isBlockedHost(endpointUrl, failClosedOnDnsError = false)) {
            logger.info { "Skipping webmention to blocked endpoint $endpointUrl" }
            recordTerminalFailure(sourceUrl, targetUrl, "webmention endpoint resolves to a blocked address")
            return
        }

        when (val result = httpClient.sendWebmention(sourceUrl, targetUrl, endpointUrl)) {
            is SendWebmentionResult.Success -> {
                logger.info { "Webmention delivered to $endpointUrl (HTTP ${result.statusCode})" }
                notificationService.recordSuccess(sourceUrl, targetUrl, result.statusCode)
            }

            is SendWebmentionResult.Failure -> {
                logger.warn { "Webmention to $endpointUrl failed: ${result.message}" }
                val attempts =
                    notificationService.recordFailure(sourceUrl, targetUrl, result.statusCode, result.message)
                val nextAttempt =
                    if (result.retryable && attempts < config.maxAttempts) {
                        nextAttemptAt(attempts)
                    } else {
                        null
                    }
                notificationService.scheduleNextAttempt(sourceUrl, targetUrl, nextAttempt)
            }
        }
    }

    private fun isSuppressed(targetUrl: String): Boolean = policies.any { it.isSuppressed(targetUrl) }

    internal fun targetUrlsOf(obj: Mf2Object): Set<String> {
        val urls = UrlExtractor.distinctUrls(Mf2TextExtractor.extractText(obj))
        return urls.filterNot(contentUrlMatcher::isOwnContentUrl).toSet()
    }

    private fun resolveEndpointForTarget(
        targetUrl: String,
        forceRediscovery: Boolean = false,
    ): String? {
        val now = Instant.now()

        if (!forceRediscovery) {
            when (val cached = endpointCacheService.lookup(targetUrl, now)) {
                is EndpointCacheResult.Fresh -> return cached.endpointUrl
                is EndpointCacheResult.Miss -> Unit
            }
        }

        val discovery = discover(targetUrl)
        val expiresAt =
            WebmentionUtil.effectiveCacheExpiry(
                cacheControl = discovery.cacheControl,
                expiresHeader = discovery.expiresHeader,
                now = now,
            )
        if (expiresAt != null) {
            endpointCacheService.store(targetUrl, discovery.endpointUrl, expiresAt)
        } else {
            endpointCacheService.evict(targetUrl)
        }
        return discovery.endpointUrl
    }

    private fun discover(targetUrl: String): EndpointDiscovery = httpClient.discoverWebmentionEndpoint(targetUrl)

    private fun nextAttemptAt(attempts: Int): Instant {
        val exponent = (attempts - 1).coerceAtLeast(0)
        val seconds =
            minOf(
                config.backoffBaseSeconds * (1L shl exponent.coerceAtMost(20)),
                config.backoffMaxSeconds,
            )
        return Instant.now().plusSeconds(seconds)
    }

    private fun recordTerminalFailure(
        sourceUrl: String,
        targetUrl: String,
        reason: String,
    ) {
        notificationService.recordFailure(sourceUrl, targetUrl, null, reason)
        notificationService.scheduleNextAttempt(sourceUrl, targetUrl, null)
    }

    private fun enqueueSend(
        sourceUrl: String,
        targetUrl: String,
        forceRediscovery: Boolean = false,
    ) {
        jobScheduler.enqueue { sendWebmention(sourceUrl, targetUrl, forceRediscovery) }
    }
}
