package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.post.query.PostQueryService
import dev.jacobandersen.bastion.webmention.internal.WebmentionService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Sends Salmentions upstream. When the responses displayed on a post change,
 * the post's permalink has changed, so every target the post already
 * delivered a webmention to is notified again and can pick up the new nested
 * responses. Reuses the idempotent outbound reconciliation, which re-sends to
 * delivered targets only and adds no new targets because the post content is
 * unchanged.
 */
@Service
class SalmentionService(
    private val postQueryService: PostQueryService,
    private val webmentionService: WebmentionService,
) {
    /** Invoked from a JobRunr job: no default parameter values. */
    fun resendAfterResponseChange(postId: UUID) {
        val post = postQueryService.findById(postId) ?: return
        if (!post.publiclyReachable) return
        logger.info { "Responses on ${post.url} changed, re-sending webmentions upstream" }
        webmentionService.reconcile(post, refresh = true)
    }
}
