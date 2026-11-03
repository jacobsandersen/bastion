package dev.jacobandersen.bastion.api.internal.post

import dev.jacobandersen.bastion.api.internal.dto.Pagination
import dev.jacobandersen.bastion.api.internal.post.dto.FeedResponse
import dev.jacobandersen.bastion.api.internal.post.dto.PostGoneResponse
import dev.jacobandersen.bastion.api.internal.post.dto.PostResponse
import dev.jacobandersen.bastion.api.internal.post.dto.SyndicationDto
import dev.jacobandersen.bastion.api.internal.post.dto.WebmentionCounts
import dev.jacobandersen.bastion.api.internal.post.dto.WebmentionDto
import dev.jacobandersen.bastion.post.read.PostFeedQuery
import dev.jacobandersen.bastion.post.read.PostLookup
import dev.jacobandersen.bastion.post.read.PostReadService
import dev.jacobandersen.bastion.post.read.PostView
import dev.jacobandersen.bastion.post.read.SyndicationLinkView
import dev.jacobandersen.bastion.webmention.read.WebmentionCountsView
import dev.jacobandersen.bastion.webmention.read.WebmentionReadService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/posts")
class PublicPostController(
    private val postReadService: PostReadService,
    private val webmentionReadService: WebmentionReadService,
) {
    @GetMapping
    fun feed(
        @RequestParam(required = false) h: List<String>?,
        @RequestParam(required = false) type: List<String>?,
        @RequestParam(required = false) tag: List<String>? = null,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = false) day: Int?,
    ): FeedResponse {
        val effectiveLimit = limit ?: 10
        val effectiveOffset = offset ?: 0
        val posts =
            postReadService.feed(
                PostFeedQuery(
                    h = h,
                    type = type,
                    tag = tag,
                    limit = effectiveLimit,
                    offset = effectiveOffset,
                    year = year,
                    month = month,
                    day = day,
                ),
            )
        val postIds = posts.map { it.id }
        val webmentions = webmentionReadService.byPostIds(postIds)
        val syndications = postReadService.syndicationLinksByPostIds(postIds)
        val items =
            posts.map { post ->
                val postWebmentions = webmentions[post.id]
                toResponse(
                    post,
                    postWebmentions?.counts ?: WebmentionCountsView.EMPTY,
                    postWebmentions?.webmentions.orEmpty().map(WebmentionDto::from),
                    syndications[post.id].orEmpty(),
                )
            }
        return FeedResponse(
            items = items,
            pagination =
                Pagination(
                    limit = effectiveLimit,
                    offset = effectiveOffset,
                    count = items.size,
                    hasMore = items.size == effectiveLimit,
                ),
        )
    }

    /**
     * Lookup by URL - primary lookup path. Frontend can pass the current page URL
     * to discover the post without parsing slug/date from the path.
     */
    @GetMapping("/lookup")
    fun lookupByUrl(
        @RequestParam url: String,
    ): ResponseEntity<Any> = resolvePost(postReadService.lookupByUrl(url))

    @GetMapping("/{slug}")
    fun bySlug(
        @PathVariable slug: String,
    ): ResponseEntity<Any> = resolvePost(postReadService.lookupBySlug(slug))

    private fun resolvePost(result: PostLookup?): ResponseEntity<Any> =
        when (result) {
            null -> {
                ResponseEntity.notFound().build()
            }

            is PostLookup.Gone -> {
                ResponseEntity
                    .status(HttpStatus.GONE)
                    .body(PostGoneResponse(result.slug, result.url, result.published))
            }

            is PostLookup.Found -> {
                val post = result.post
                val counts = webmentionReadService.countsForPost(post.id)
                val webmentions =
                    webmentionReadService.verifiedByPost(post.id).sortedBy { it.firstSeenAt }.map(WebmentionDto::from)
                ResponseEntity.ok(toResponse(post, counts, webmentions, postReadService.syndicationLinks(post.id)))
            }
        }

    private fun toResponse(
        post: PostView,
        counts: WebmentionCountsView,
        webmentions: List<WebmentionDto>?,
        syndications: List<SyndicationLinkView>? = null,
    ): PostResponse =
        PostResponse(
            id = post.id.toString(),
            slug = post.slug,
            url = post.url,
            h = post.h,
            type = post.type,
            published = post.published,
            updated = post.updated,
            name = post.name,
            summary = post.summary,
            content = post.content,
            contentHtml = post.contentHtml,
            category = post.category,
            properties = post.properties,
            webmentionCounts = WebmentionCounts.from(counts),
            webmentions = webmentions,
            syndications = syndications?.map { SyndicationDto(uid = it.uid, name = it.name, url = it.url) },
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "bad request")))
}
