package dev.jacobandersen.bastion.websub.internal

import dev.jacobandersen.bastion.util.HttpUtil
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import dev.jacobandersen.bastion.websub.internal.http.PublishResult
import dev.jacobandersen.bastion.websub.internal.http.WebsubHttpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

class WebsubPublishException(
    message: String,
) : RuntimeException(message)

/**
 * Publishes feed topics to each configured WebSub hub.
 *
 * Dispatch is fire-and-forget: [publish] enqueues one JobRunr job per hub
 * and topic, and JobRunr's exponential backoff retries any job that fails with a
 * retryable error.
 */
@Service
class WebsubPublisher(
    private val jobScheduler: JobScheduler,
    private val httpClient: WebsubHttpClient,
    private val config: WebsubConfig,
) {
    private val hubs: List<String>
        get() = config.hubs.map(String::trim).filter(String::isNotBlank)

    fun publish(topicUrls: Collection<String>) {
        if (hubs.isEmpty() || topicUrls.isEmpty()) {
            logger.debug { "WebSub publishing skipped (no hubs or topics)" }
            return
        }

        hubs.forEach { hub -> topicUrls.forEach { topic -> enqueuePublish(hub, topic) } }
    }

    fun publishToHub(
        hubUrl: String,
        topicUrl: String,
    ) {
        if (HttpUtil.isBlockedHost(hubUrl, failClosedOnDnsError = false)) {
            logger.warn { "Skipping publish to blocked hub $hubUrl" }
            return
        }

        logger.info { "Publishing WebSub topic $topicUrl to $hubUrl..." }
        when (val result = httpClient.publish(hubUrl, topicUrl)) {
            is PublishResult.Success -> {
                logger.info { "WebSub publish delivered to $hubUrl (HTTP ${result.statusCode})" }
            }

            is PublishResult.Failure -> {
                if (result.retryable) {
                    throw WebsubPublishException("WebSub publish to $hubUrl failed: ${result.message}")
                }
                logger.warn { "WebSub publish to $hubUrl permanently failed: ${result.message}" }
            }
        }
    }

    private fun enqueuePublish(
        hubUrl: String,
        topicUrl: String,
    ) {
        jobScheduler.enqueue { publishToHub(hubUrl, topicUrl) }
    }
}
