package dev.jacobandersen.bastion.post.query

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.store.PostService
import dev.jacobandersen.bastion.post.internal.url.generatePostUrl
import dev.jacobandersen.bastion.url.ContentUrlService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Authenticated/internal post reads. This is the query side used by the
 * Micropub source endpoint, webmention reconciliation, and syndication jobs.
 * Public unauthenticated reads belong to [dev.jacobandersen.bastion.post.read.PostReadService].
 */
@Service
class PostQueryService(
    private val postService: PostService,
    private val urlService: ContentUrlService,
) {
    fun byUrl(url: String): PostQueryResult {
        val slug =
            urlService.extractSlug(url)
                ?: return PostQueryResult.Invalid("Invalid URL for this Bastion instance: $url")
        val post = postService.findBySlug(slug) ?: return PostQueryResult.NotFound
        val view = post.toStateView()

        return if (post.deleted) PostQueryResult.Gone(view) else PostQueryResult.Found(view)
    }

    fun findById(id: UUID): PostStateView? = postService.findById(id)?.toStateView()

    fun list(
        limitArg: Int?,
        offsetArg: Int?,
    ): List<PostStateView> {
        val limit = limitArg ?: 10
        val offset = offsetArg ?: 0

        require(limit in 1..PostService.MAX_PAGE_SIZE) {
            "limit must be an integer between 1 and ${PostService.MAX_PAGE_SIZE}"
        }
        require(offset >= 0) { "offset must be a non-negative integer" }
        require(offset % limit == 0) { "offset must be a multiple of limit ($offset % $limit != 0)" }

        return postService.findAllPosts(limit, offset).map { it.toStateView() }
    }

    private fun Post.toStateView(): PostStateView =
        PostStateView(
            id = id,
            slug = slug,
            url = urlService.generatePostUrl(this),
            h = h,
            type = type,
            post = post,
            deleted = deleted,
            publiclyReachable = publiclyReachable,
            isFeedVisible = isFeedVisible,
        )
}
