package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.entity.PostSyndicationEntity
import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.store.PostService
import dev.jacobandersen.bastion.post.internal.types.PostTypeCatalog
import dev.jacobandersen.bastion.post.internal.url.generatePostUrl
import dev.jacobandersen.bastion.url.ContentUrlService
import dev.jacobandersen.bastion.util.HttpUtil
import dev.jacobandersen.microformats2.firstText
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Reconciles syndicated copies against the current post and requested target
 * set. Dispatch is best-effort: failures are logged and never fail the
 * originating post command.
 */
@Service
class SyndicationService(
    private val jobScheduler: JobScheduler,
    private val config: SyndicationConfig,
    private val httpClient: SyndicationHttpClient,
    private val postSyndicationService: PostSyndicationService,
    private val postService: PostService,
    private val postTypeCatalog: PostTypeCatalog,
    private val urlService: ContentUrlService,
) {
    @Transactional
    fun reconcile(
        postId: UUID,
        forceUpdate: Boolean,
    ) {
        val post = postService.findById(postId) ?: return
        val canonicalUrl = urlService.generatePostUrl(post)
        val currentUpdated = post.post.firstText("updated")
        val now = Instant.now()
        val staleBefore = now.minus(CLAIM_LEASE)

        postSyndicationService.findByPostId(postId).forEach { record ->
            val target = config.targetByUid(record.targetUid)
            if (target == null) {
                postSyndicationService.remove(postId, record.targetUid)
                return@forEach
            }

            if (!record.desired) {
                if (record.syndicatedUrl == null && record.pendingAction == null) {
                    postSyndicationService.remove(postId, record.targetUid)
                } else {
                    retractIfNeeded(post, record, target, now, staleBefore)
                }
                return@forEach
            }

            if (!post.publiclyReachable) {
                retractIfNeeded(post, record, target, now, staleBefore)
                return@forEach
            }

            if (record.pendingAction != null) {
                return@forEach
            }

            val sourceUrl = record.sourceUrl
            when {
                record.syndicatedUrl == null -> {
                    if (target.supports(SyndicationAction.CREATE)) {
                        val claimToken = postSyndicationService.claim(post.id, target.uid, "create", now, staleBefore) ?: return@forEach
                        enqueueCreate(post, target.uid, claimToken)
                    }
                }

                sourceUrl != null && sourceUrl != canonicalUrl -> {
                    if (target.supports(SyndicationAction.DELETE) && target.supports(SyndicationAction.CREATE)) {
                        val claimToken = postSyndicationService.claim(post.id, target.uid, "rebase", now, staleBefore) ?: return@forEach
                        enqueueRebase(post, target.uid, sourceUrl, claimToken)
                    } else {
                        retractIfNeeded(post, record, target, now, staleBefore)
                    }
                }

                else -> {
                    if (target.supports(SyndicationAction.UPDATE) &&
                        (forceUpdate || record.lastSyncedUpdated != currentUpdated)
                    ) {
                        val claimToken = postSyndicationService.claim(post.id, target.uid, "update", now, staleBefore) ?: return@forEach
                        enqueueUpdate(post, target, claimToken)
                    }
                }
            }
        }
    }

    private fun retractIfNeeded(
        post: Post,
        record: PostSyndicationEntity,
        target: SyndicationConfig.Target,
        now: Instant,
        staleBefore: Instant,
    ) {
        if (record.syndicatedUrl == null) return
        if (!target.supports(SyndicationAction.DELETE)) return

        val claimToken = postSyndicationService.claim(post.id, target.uid, "delete", now, staleBefore) ?: return
        val sourceUrl = record.sourceUrl ?: urlService.generatePostUrl(post)
        enqueueDelete(post, target.uid, sourceUrl, claimToken)
    }

    private fun enqueueCreate(
        post: Post,
        targetUid: String,
        claimToken: UUID,
    ) {
        jobScheduler.enqueue<SyndicationService>(claimToken) {
            it.runCreateJob(post.id, targetUid, claimToken)
        }
    }

    private fun enqueueDelete(
        post: Post,
        targetUid: String,
        sourceUrl: String,
        claimToken: UUID,
    ) {
        jobScheduler.enqueue<SyndicationService>(claimToken) {
            it.runDeleteJob(post.id, targetUid, sourceUrl, claimToken)
        }
    }

    private fun enqueueRebase(
        post: Post,
        targetUid: String,
        previousUrl: String,
        claimToken: UUID,
    ) {
        jobScheduler.enqueue<SyndicationService>(claimToken) {
            it.runRebaseJob(post.id, targetUid, previousUrl, claimToken)
        }
    }

    private fun enqueueUpdate(
        post: Post,
        target: SyndicationConfig.Target,
        claimToken: UUID,
    ) {
        val canonicalUrl = urlService.generatePostUrl(post)
        val update =
            httpClient.serializeExcerptUpdate(
                post,
                canonicalUrl,
                config.effectiveMaxGraphemes(target),
                postTypeCatalog.syndicationFormatFor(post.type),
            )
        jobScheduler.enqueue<SyndicationService>(claimToken) {
            it.runUpdateJob(post.id, target.uid, update, claimToken)
        }
    }

    @Job(retries = 0)
    fun runCreateJob(
        postId: UUID,
        targetUid: String,
        claimToken: UUID,
    ) {
        try {
            val record = postSyndicationService.findByPostIdAndTargetUid(postId, targetUid)
            if (record == null || record.pendingToken != claimToken) {
                logger.warn { "Skipping syndication create for post $postId to target \"$targetUid\": claim no longer active" }
                return
            }
            val post = postService.findById(postId)
            if (post == null) {
                logger.warn { "Skipping syndication create: post $postId no longer exists" }
                return
            }
            if (!post.publiclyReachable) {
                logger.warn { "Skipping syndication create for post $postId: post is no longer publicly reachable" }
                return
            }
            val target = config.targetByUid(targetUid)
            if (target == null) {
                logger.warn { "Skipping syndication create: unknown target \"$targetUid\"" }
                return
            }
            if (HttpUtil.isBlockedHost(target.endpoint, failClosedOnDnsError = false)) {
                logger.warn { "Skipping syndication create for post $postId to blocked target \"$targetUid\"" }
                return
            }

            val canonicalUrl = urlService.generatePostUrl(post)
            when (val result = httpClient.sendCreate(target, syndicatedPost(post, target, canonicalUrl))) {
                is SyndicationSendResult.Success -> {
                    logger.info { "Syndicated post $postId to target \"$targetUid\" (HTTP ${result.statusCode})" }
                    postSyndicationService.recordOutcome(
                        postId,
                        targetUid,
                        claimToken,
                        result.location ?: canonicalUrl,
                        canonicalUrl,
                        post.post.firstText("updated"),
                    )
                }

                is SyndicationSendResult.Failure -> {
                    logger.warn { "Syndication create to target \"$targetUid\" for post $postId failed: ${result.message}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Syndication create to target \"$targetUid\" for post $postId failed unexpectedly" }
        } finally {
            postSyndicationService.clearPending(postId, targetUid, claimToken)
        }
    }

    @Job(retries = 0)
    fun runDeleteJob(
        postId: UUID,
        targetUid: String,
        sourceUrl: String,
        claimToken: UUID,
    ) {
        try {
            val record = postSyndicationService.findByPostIdAndTargetUid(postId, targetUid)
            if (record == null || record.pendingToken != claimToken) {
                logger.warn { "Skipping syndication delete for post $postId to target \"$targetUid\": claim no longer active" }
                return
            }
            val target = config.targetByUid(targetUid)
            if (target == null) {
                logger.warn { "Skipping syndication delete: unknown target \"$targetUid\"" }
                return
            }
            if (HttpUtil.isBlockedHost(target.endpoint, failClosedOnDnsError = false)) {
                logger.warn { "Skipping syndication delete for post $postId to blocked target \"$targetUid\"" }
                return
            }

            when (val result = httpClient.sendDelete(target, sourceUrl)) {
                is SyndicationSendResult.Success -> {
                    logger.info { "Syndication delete sent for post $postId to target \"$targetUid\" (HTTP ${result.statusCode})" }
                    postSyndicationService.markRetracted(postId, targetUid, claimToken)
                    val current = postSyndicationService.findByPostIdAndTargetUid(postId, targetUid)
                    if (current != null && !current.desired) {
                        postSyndicationService.remove(postId, targetUid)
                    }
                }

                is SyndicationSendResult.Failure -> {
                    logger.warn { "Syndication delete to target \"$targetUid\" for post $postId failed: ${result.message}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Syndication delete to target \"$targetUid\" for post $postId failed unexpectedly" }
        } finally {
            postSyndicationService.clearPending(postId, targetUid, claimToken)
        }
    }

    @Job(retries = 0)
    fun runRebaseJob(
        postId: UUID,
        targetUid: String,
        previousUrl: String,
        claimToken: UUID,
    ) {
        try {
            val record = postSyndicationService.findByPostIdAndTargetUid(postId, targetUid)
            if (record == null || record.pendingToken != claimToken) {
                logger.warn { "Skipping syndication rebase for post $postId to target \"$targetUid\": claim no longer active" }
                return
            }
            val post = postService.findById(postId)
            if (post == null) {
                logger.warn { "Skipping syndication rebase: post $postId no longer exists" }
                return
            }
            val target = config.targetByUid(targetUid)
            if (target == null) {
                logger.warn { "Skipping syndication rebase: unknown target \"$targetUid\"" }
                return
            }
            if (HttpUtil.isBlockedHost(target.endpoint, failClosedOnDnsError = false)) {
                logger.warn { "Skipping syndication rebase for post $postId to blocked target \"$targetUid\"" }
                return
            }

            when (val deleteResult = httpClient.sendDelete(target, previousUrl)) {
                is SyndicationSendResult.Success -> {
                    logger.info {
                        "Syndication rebase removed old copy for post $postId at target \"$targetUid\" (HTTP ${deleteResult.statusCode})"
                    }
                }

                is SyndicationSendResult.Failure -> {
                    logger.warn {
                        "Syndication rebase could not remove old copy at target \"$targetUid\" for post $postId: ${deleteResult.message}"
                    }
                }
            }

            if (!post.publiclyReachable) {
                logger.warn { "Skipping syndication rebase re-create for post $postId: post is no longer publicly reachable" }
                return
            }

            val canonicalUrl = urlService.generatePostUrl(post)
            when (val createResult = httpClient.sendCreate(target, syndicatedPost(post, target, canonicalUrl))) {
                is SyndicationSendResult.Success -> {
                    logger.info { "Syndication rebase re-created post $postId at target \"$targetUid\" (HTTP ${createResult.statusCode})" }
                    postSyndicationService.recordOutcome(
                        postId,
                        targetUid,
                        claimToken,
                        createResult.location ?: canonicalUrl,
                        canonicalUrl,
                        post.post.firstText("updated"),
                    )
                }

                is SyndicationSendResult.Failure -> {
                    logger.warn { "Syndication rebase create to target \"$targetUid\" for post $postId failed: ${createResult.message}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Syndication rebase to target \"$targetUid\" for post $postId failed unexpectedly" }
        } finally {
            postSyndicationService.clearPending(postId, targetUid, claimToken)
        }
    }

    @Job(retries = 0)
    fun runUpdateJob(
        postId: UUID,
        targetUid: String,
        update: SyndicationUpdate,
        claimToken: UUID,
    ) {
        try {
            val record = postSyndicationService.findByPostIdAndTargetUid(postId, targetUid)
            if (record == null || record.pendingToken != claimToken) {
                logger.warn { "Skipping syndication update for post $postId to target \"$targetUid\": claim no longer active" }
                return
            }
            val post = postService.findById(postId)
            if (post == null) {
                logger.warn { "Skipping syndication update: post $postId no longer exists" }
                return
            }
            val target = config.targetByUid(targetUid)
            if (target == null) {
                logger.warn { "Skipping syndication update: unknown target \"$targetUid\"" }
                return
            }
            if (HttpUtil.isBlockedHost(target.endpoint, failClosedOnDnsError = false)) {
                logger.warn { "Skipping syndication update for post $postId to blocked target \"$targetUid\"" }
                return
            }

            val canonicalUrl = urlService.generatePostUrl(post)
            when (val result = httpClient.sendUpdate(target, canonicalUrl, update)) {
                is SyndicationSendResult.Success -> {
                    logger.info { "Syndication update sent for post $postId to target \"$targetUid\" (HTTP ${result.statusCode})" }
                    postSyndicationService.recordSynced(postId, targetUid, claimToken, post.post.firstText("updated"))
                }

                is SyndicationSendResult.Failure -> {
                    logger.warn { "Syndication update to target \"$targetUid\" for post $postId failed: ${result.message}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Syndication update to target \"$targetUid\" for post $postId failed unexpectedly" }
        } finally {
            postSyndicationService.clearPending(postId, targetUid, claimToken)
        }
    }

    private fun syndicatedPost(
        post: Post,
        target: SyndicationConfig.Target,
        canonicalUrl: String,
    ) = SyndicationContentMapper.build(
        post,
        canonicalUrl,
        config.effectiveMaxGraphemes(target),
        postTypeCatalog.syndicationFormatFor(post.type),
    )

    companion object {
        private val CLAIM_LEASE: Duration = Duration.ofMinutes(5)
    }
}
