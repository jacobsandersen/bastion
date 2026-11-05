package dev.jacobandersen.bastion.websub.internal.subscriber

import dev.jacobandersen.bastion.util.HttpUtil
import dev.jacobandersen.bastion.websub.events.WebsubContentNotifiedEvent
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import dev.jacobandersen.bastion.websub.internal.http.PublishResult
import dev.jacobandersen.bastion.websub.internal.http.WebsubHttpClient
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionService
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState.ACTIVE
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState.DENIED
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState.FAILED
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState.PENDING
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionState.UNSUBSCRIBING
import dev.jacobandersen.bastion.websub.subscription.WebsubSubscriptionView
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jobrunr.scheduling.JobScheduler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

private val logger = KotlinLogging.logger {}

class WebsubSubscriptionException(
    message: String,
) : RuntimeException(message)

enum class DeliveryOutcome {
    /** Signed correctly; an event was published. */
    ACCEPTED,

    /** Unsigned, badly signed or oversized. Acknowledged with 2xx as the spec requires, but dropped. */
    IGNORED,

    /** No live subscription: answer 410 so the hub stops delivering. */
    GONE,
}

/**
 * WebSub subscriber (WebSub section 5-7). Subscribe/unsubscribe requests go out as
 * JobRunr jobs so a slow or failing hub never blocks the caller; transient
 * hub errors are retried by JobRunr, permanent ones mark the subscription
 * [FAILED]. The recurring renewal job renews leases and re-sends requests a
 * hub never verified.
 */
@Service
class WebsubSubscriber(
    private val repository: WebsubSubscriptionRepository,
    private val httpClient: WebsubHttpClient,
    private val jobScheduler: JobScheduler,
    private val eventPublisher: ApplicationEventPublisher,
    private val config: WebsubConfig,
    @Value($$"${bastion.public-url}") private val publicUrl: String,
) : WebsubSubscriptionService {
    private val random = SecureRandom()

    @Transactional
    override fun subscribe(
        topic: String,
        hub: String,
    ): UUID {
        require(isHttpUrl(topic)) { "WebSub topic must be an absolute http(s) URL" }
        require(isHttpUrl(hub)) { "WebSub hub must be an absolute http(s) URL" }

        val now = Instant.now()
        val existing = repository.findByTopicAndHub(topic, hub)
        if (existing != null && existing.state in setOf(ACTIVE, PENDING)) return existing.id

        val id = existing?.id ?: UUID.randomUUID()
        if (existing == null) {
            repository.insert(id, topic, hub, newSecret(), now)
        } else {
            repository.markRequested(id, PENDING, now)
        }
        enqueueAfterCommit(id, MODE_SUBSCRIBE)
        return id
    }

    @Transactional
    override fun unsubscribe(id: UUID) {
        val subscription = repository.find(id) ?: return
        if (subscription.state in setOf(DENIED, FAILED)) {
            repository.delete(id)
            return
        }
        repository.markRequested(id, UNSUBSCRIBING, Instant.now())
        enqueueAfterCommit(id, MODE_UNSUBSCRIBE)
    }

    @Transactional(readOnly = true)
    override fun find(id: UUID): WebsubSubscriptionView? = repository.find(id)?.toView()

    /** JobRunr entry point: sends one subscribe or unsubscribe request to the hub. */
    fun sendRequest(
        id: UUID,
        mode: String,
    ) {
        val subscription = repository.find(id) ?: return
        val expected = if (mode == MODE_SUBSCRIBE) setOf(PENDING, ACTIVE) else setOf(UNSUBSCRIBING)
        if (subscription.state !in expected) {
            logger.debug { "Skipping WebSub $mode for ${subscription.topic}: state is now ${subscription.state}" }
            return
        }

        if (HttpUtil.isBlockedHost(subscription.hub, failClosedOnDnsError = false)) {
            logger.warn { "Not sending WebSub $mode to blocked hub ${subscription.hub}" }
            finishPermanently(subscription, mode, "hub host is blocked")
            return
        }

        logger.info { "Sending WebSub $mode for ${subscription.topic} to ${subscription.hub}" }
        val result =
            httpClient.subscription(
                hubUrl = subscription.hub,
                mode = mode,
                topicUrl = subscription.topic,
                callbackUrl = callbackUrl(id),
                secret = subscription.secret.takeIf { mode == MODE_SUBSCRIBE },
                leaseSeconds = config.subscriber.leaseSeconds.takeIf { mode == MODE_SUBSCRIBE },
            )
        when (result) {
            is PublishResult.Success -> {
                logger.info { "Hub ${subscription.hub} accepted WebSub $mode for ${subscription.topic}; awaiting verification" }
            }

            is PublishResult.Failure -> {
                if (result.retryable) {
                    throw WebsubSubscriptionException("WebSub $mode to ${subscription.hub} failed: ${result.message}")
                }
                logger.warn { "Hub ${subscription.hub} rejected WebSub $mode for ${subscription.topic}: ${result.message}" }
                finishPermanently(subscription, mode, result.message)
            }
        }
    }

    /**
     * Handles a hub's verification of intent (WebSub 5.3) or a denial (5.2).
     * Returns the body to answer with 200, or null to answer 404 so the hub
     * abandons a request Bastion does not want.
     */
    @Transactional
    fun verifyIntent(
        id: UUID,
        mode: String?,
        topic: String?,
        challenge: String?,
        leaseSeconds: Long?,
        reason: String?,
    ): String? {
        val subscription = repository.find(id) ?: return null
        if (topic != subscription.topic) return null
        val now = Instant.now()

        return when (mode) {
            MODE_SUBSCRIBE -> {
                if (challenge.isNullOrEmpty() || subscription.state !in setOf(PENDING, ACTIVE)) return null
                repository.markActive(id, leaseSeconds, now)
                logger.info { "WebSub subscription to ${subscription.topic} verified (lease ${leaseSeconds}s)" }
                challenge
            }

            MODE_UNSUBSCRIBE -> {
                if (challenge.isNullOrEmpty() || subscription.state != UNSUBSCRIBING) return null
                repository.delete(id)
                logger.info { "WebSub unsubscription from ${subscription.topic} verified" }
                challenge
            }

            MODE_DENIED -> {
                repository.markFailed(id, DENIED, reason ?: "denied by hub", now)
                logger.warn { "Hub ${subscription.hub} denied subscription to ${subscription.topic}: $reason" }
                ""
            }

            else -> {
                null
            }
        }
    }

    /** Handles a content distribution request (WebSub 7-8). */
    @Transactional
    fun receiveContent(
        id: UUID,
        signature: String?,
        body: ByteArray?,
    ): DeliveryOutcome {
        val subscription = repository.find(id) ?: return DeliveryOutcome.GONE
        if (subscription.state !in setOf(ACTIVE, PENDING)) return DeliveryOutcome.GONE
        if (body == null) {
            logger.warn { "Ignoring oversized WebSub delivery for ${subscription.topic}" }
            return DeliveryOutcome.IGNORED
        }
        if (!HubSignature.isValid(signature, subscription.secret, body)) {
            logger.warn { "Ignoring WebSub delivery for ${subscription.topic} with a missing or invalid signature" }
            return DeliveryOutcome.IGNORED
        }

        val now = Instant.now()
        repository.touchNotified(id, now)
        eventPublisher.publishEvent(WebsubContentNotifiedEvent(UUID.randomUUID(), id, subscription.topic, now))
        return DeliveryOutcome.ACCEPTED
    }

    /** JobRunr entry point: renews expiring leases and re-sends requests hubs never verified. */
    fun renewDue() {
        val now = Instant.now()
        val due =
            repository.dueForRequest(
                renewBy = now.plus(config.subscriber.renewBefore),
                staleBefore = now.minus(config.subscriber.pendingRetryAfter),
            )
        due.forEach { subscription ->
            when (subscription.state) {
                UNSUBSCRIBING -> {
                    // The hub never confirmed; the lease will lapse on its own.
                    repository.delete(subscription.id)
                }

                else -> {
                    repository.markRequested(subscription.id, subscription.state, now)
                    enqueue(subscription.id, MODE_SUBSCRIBE)
                }
            }
        }
        if (due.isNotEmpty()) logger.info { "Renewed or retried ${due.size} WebSub subscriptions" }
    }

    internal fun callbackUrl(id: UUID): String = "${publicUrl.trim().trimEnd('/')}$CALLBACK_PATH/$id"

    private fun finishPermanently(
        subscription: WebsubSubscription,
        mode: String,
        reason: String,
    ) {
        if (mode == MODE_SUBSCRIBE) {
            repository.markFailed(subscription.id, FAILED, reason, Instant.now())
        } else {
            repository.delete(subscription.id)
        }
    }

    private fun enqueueAfterCommit(
        id: UUID,
        mode: String,
    ) {
        // The job reads the row with its own connection, so it must not start before this transaction commits.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = enqueue(id, mode)
                },
            )
        } else {
            enqueue(id, mode)
        }
    }

    private fun enqueue(
        id: UUID,
        mode: String,
    ) {
        jobScheduler.enqueue { sendRequest(id, mode) }
    }

    private fun newSecret(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(random::nextBytes))

    private fun isHttpUrl(value: String): Boolean =
        runCatching { URI(value) }
            .getOrNull()
            ?.let { it.isAbsolute && it.scheme.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() }
            ?: false

    private fun WebsubSubscription.toView() = WebsubSubscriptionView(id, topic, hub, state, leaseExpiresAt, lastError)

    companion object {
        const val CALLBACK_PATH = "/websub/callback"
        const val MODE_SUBSCRIBE = "subscribe"
        const val MODE_UNSUBSCRIBE = "unsubscribe"
        const val MODE_DENIED = "denied"
        private const val SECRET_BYTES = 32
    }
}
