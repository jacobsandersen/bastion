package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.url.ContentUrlMatcher
import dev.jacobandersen.bastion.webmention.internal.data.domain.ReceivedWebmentionAnalysis
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionInteraction.MENTION
import dev.jacobandersen.bastion.webmention.internal.data.service.ReceivedWebmentionService
import dev.jacobandersen.bastion.webmention.internal.http.WebmentionSourceFetcher
import dev.jacobandersen.microformats2.Mf2Parser
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Asynchronously verifies received webmentions: fetches the source document,
 * confirms it mentions the target (section 3.2.2) and records the outcome
 * (section 3.2.4), extracting interaction type, author and content data when the
 * source is valid.
 *
 * When the outcome changes what the target post displays (a new, edited or
 * removed response, including nested responses), a Salmention resend is
 * enqueued. An unchanged re-verification enqueues nothing, which is what ends
 * webmention ping-pong between sites.
 */
@Service
class WebmentionReceiverService(
    private val notificationService: ReceivedWebmentionService,
    private val sourceFetcher: WebmentionSourceFetcher,
    private val parser: Mf2Parser,
    private val contentUrlMatcher: ContentUrlMatcher,
    private val jobScheduler: JobScheduler,
    private val salmentionService: SalmentionService,
) {
    fun verify(
        sourceUrl: String,
        targetUrl: String,
        postId: UUID,
    ) {
        logger.info { "Verifying received webmention from $sourceUrl for $targetUrl" }
        notificationService.ensurePending(sourceUrl, targetUrl, postId)

        val fetch = sourceFetcher.fetch(sourceUrl)
        val verification = WebmentionSourceVerifier.verify(fetch, targetUrl, parser)

        val update =
            when (verification.verdict) {
                SourceVerdict.GONE -> {
                    logger.info { "Source $sourceUrl is gone, marking webmention deleted" }
                    notificationService.markDeleted(sourceUrl, postId)
                }

                SourceVerdict.NO_LINK -> {
                    logger.warn { "Source $sourceUrl does not link to target $targetUrl" }
                    notificationService.markRejected(sourceUrl, postId, "source does not link to the target")
                }

                SourceVerdict.UNREACHABLE -> {
                    logger.warn { "Unable to verify source $sourceUrl: ${verification.reason}" }
                    notificationService.markError(sourceUrl, postId, verification.reason ?: "unable to fetch source")
                }

                SourceVerdict.VERIFIED -> {
                    val analysis =
                        verification.parse
                            ?.let { ReceivedWebmentionAnalyzer.analyze(it, sourceUrl) }
                            ?.let(::withoutOwnContent)
                            ?: ReceivedWebmentionAnalysis(interaction = MENTION, primary = null)
                    logger.info {
                        "Verified webmention from $sourceUrl as ${analysis.interaction} with ${analysis.nested.size} nested responses"
                    }
                    notificationService.markVerified(sourceUrl, postId, analysis)
                }
            }

        if (update.changed) {
            logger.info { "Responses on post $postId changed, scheduling salmention" }
            jobScheduler.enqueue { salmentionService.resendAfterResponseChange(postId) }
        }
    }

    /** Our own replies shown on someone else's page are already ours; never import them as responses. */
    private fun withoutOwnContent(analysis: ReceivedWebmentionAnalysis): ReceivedWebmentionAnalysis =
        analysis.copy(nested = analysis.nested.filterNot { contentUrlMatcher.isOwnContentUrl(it.url) })
}
