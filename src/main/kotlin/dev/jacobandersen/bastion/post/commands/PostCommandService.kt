package dev.jacobandersen.bastion.post.commands

import com.github.slugify.Slugify
import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.store.PostService
import dev.jacobandersen.bastion.post.internal.store.applyPatch
import dev.jacobandersen.bastion.post.internal.syndication.PostSyndicationService
import dev.jacobandersen.bastion.post.internal.syndication.SyndicationAction
import dev.jacobandersen.bastion.post.internal.syndication.SyndicationConfig
import dev.jacobandersen.bastion.post.internal.url.generatePostUrl
import dev.jacobandersen.bastion.url.ContentUrlService
import dev.jacobandersen.bastion.util.StringUtil.excerpt
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.firstText
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class PostCommandService(
    private val slugify: Slugify,
    private val postService: PostService,
    private val postSyndicationService: PostSyndicationService,
    private val syndicationConfig: SyndicationConfig,
    private val urlService: ContentUrlService,
) {
    @Transactional
    fun create(command: CreatePostCommand): PostCommandResult {
        val obj = command.post.withoutUntaggedCategory()
        val slug = postService.deduplicateSlug(command.requestedSlug?.takeIf { it.isNotBlank() } ?: deriveSlug(obj))

        val post =
            try {
                postService.create(slug, command.status, command.visibility, deleted = false, post = obj)
            } catch (e: IllegalArgumentException) {
                throw PostCommandException("Invalid published value: ${e.message}")
            }

        syncSyndication(post.id, command.syndicationTargetUids + syndicationConfig.autoTargetUids())
        logger.info { "Created post ${post.id} at ${urlService.generatePostUrl(post)}" }
        return post.toResult()
    }

    @Transactional
    fun update(command: UpdatePostCommand): PostCommandResult {
        val post = findByUrl(command.url)
        val obj = post.post.applyPatch(command.patch).withoutUntaggedCategory()
        val targetSlug =
            when {
                command.requestedSlug == null || command.requestedSlug == post.slug -> post.slug
                else -> postService.deduplicateSlug(command.requestedSlug, post.slug)
            }

        val updated =
            post.copy(
                slug = targetSlug,
                status = command.status ?: post.status,
                visibility = command.visibility ?: post.visibility,
            )

        val saved =
            try {
                postService.updatePost(updated, obj)
            } catch (e: OptimisticLockingFailureException) {
                throw PostCommandConflictException("The post was modified by another request. Reload it and retry.")
            } catch (e: IllegalArgumentException) {
                throw PostCommandException("Invalid published value: ${e.message}")
            }

        command.syndicationTargets?.let { applySyndicationChange(saved.id, it) }
        return saved.toResult(slugChanged = targetSlug != post.slug)
    }

    @Transactional
    fun delete(command: DeletePostCommand): PostCommandResult {
        val post = findByUrl(command.url)
        val saved =
            try {
                postService.updatePost(post.copy(deleted = command.deleted), post.post)
            } catch (e: OptimisticLockingFailureException) {
                throw PostCommandConflictException("The post was modified by another request. Reload it and retry.")
            } catch (e: IllegalArgumentException) {
                throw PostCommandException("Invalid published value: ${e.message}")
            }
        return saved.toResult()
    }

    private fun findByUrl(url: String): Post {
        val slug =
            urlService.extractSlug(url)
                ?: throw PostCommandException("Invalid URL for this Bastion instance: $url")
        return postService.findBySlug(slug) ?: throw PostCommandException("Post not found for URL: $url")
    }

    private fun syncSyndication(
        postId: UUID,
        requestedUids: Collection<String>,
    ) {
        val targets = syndicationConfig.targetsSupporting(requestedUids, SyndicationAction.CREATE)
        postSyndicationService.syncDesiredTargets(postId, targets)
    }

    private fun applySyndicationChange(
        postId: UUID,
        change: SyndicationTargetChange,
    ) {
        val current =
            postSyndicationService
                .findByPostId(postId)
                .filter { it.desired }
                .map { it.targetUid }
                .toSet()
        val desired =
            when {
                change.replace != null -> {
                    change.replace.toSet()
                }

                else -> {
                    val retained = if (change.removeAll) emptySet() else current - change.remove.toSet()
                    retained + change.add
                }
            }
        // Auto targets already attached to this post cannot be dropped by a client; posts without one are left alone.
        val keptAuto = syndicationConfig.autoTargetUids().filter { it in current }
        syncSyndication(postId, desired + keptAuto)
    }

    private fun deriveSlug(obj: Mf2Object): String {
        val name = obj.firstText("name")
        if (name != null) {
            return slugify.slugify(name.excerpt(MAX_SLUG_SOURCE_LENGTH))
        }

        val content = obj.firstText("content")
        if (content != null) {
            return slugify.slugify(content.excerpt(MAX_SLUG_SOURCE_LENGTH))
        }

        return UUID.randomUUID().toString()
    }

    private fun Post.toResult(slugChanged: Boolean = false): PostCommandResult =
        PostCommandResult(
            id = id,
            slug = slug,
            url = urlService.generatePostUrl(this),
            publiclyReachable = publiclyReachable,
            deleted = deleted,
            slugChanged = slugChanged,
        )

    companion object {
        private const val MAX_SLUG_SOURCE_LENGTH = 30
    }
}
