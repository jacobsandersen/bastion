package dev.jacobandersen.bastion.post.read

import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.model.PostSyndication
import dev.jacobandersen.bastion.post.internal.repository.PostRepository
import dev.jacobandersen.bastion.post.internal.store.PostService
import dev.jacobandersen.bastion.post.internal.syndication.PostSyndicationService
import dev.jacobandersen.bastion.post.internal.types.PostTypeCatalog
import dev.jacobandersen.bastion.post.internal.url.generatePostUrl
import dev.jacobandersen.bastion.post.types.PostMf2Type
import dev.jacobandersen.bastion.post.types.PostTagFilter
import dev.jacobandersen.bastion.url.ContentUrlService
import dev.jacobandersen.microformats2.firstText
import dev.jacobandersen.microformats2.htmls
import dev.jacobandersen.microformats2.texts
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Read queries over published posts for public (unauthenticated) consumers.
 * Lists return only PUBLIC posts; direct lookups additionally allow UNLISTED.
 * PRIVATE and DRAFT posts are never returned here (private is only reachable
 * through the authenticated Micropub source query). A post that was publicly
 * reachable and is now soft-deleted surfaces as [PostLookup.Gone].
 */
@Service
class PostReadService(
    private val postService: PostService,
    private val postRepository: PostRepository,
    private val postSyndicationService: PostSyndicationService,
    private val postTypeCatalog: PostTypeCatalog,
    private val urlService: ContentUrlService,
    private val zone: ZoneId,
) {
    fun feed(query: PostFeedQuery): List<PostView> {
        validatePagination(query.limit, query.offset)

        val mf2Types = parseMf2Types(query.h)
        val types = parsePostTypes(query.type)
        val tagFilter = PostTagFilter.parse(query.tag)
        val range = resolveDateRange(query.year, query.month, query.day)

        val posts =
            if (range == null) {
                postService.findFeedPosts(mf2Types, types, query.limit, query.offset, null, null, tagFilter)
            } else {
                postService.findFeedPosts(
                    mf2Types,
                    types,
                    query.limit,
                    query.offset,
                    range.first,
                    range.second,
                    tagFilter,
                )
            }

        return posts.map(::toView)
    }

    /**
     * Resolves a direct post lookup. Returns null when the post does not exist
     * or was never publicly reachable (draft/private); [PostLookup.Gone] when
     * the post existed publicly and has since been deleted.
     */
    fun lookupBySlug(slug: String): PostLookup? {
        val found = postService.findBySlug(slug) ?: return null
        return toLookup(found)
    }

    fun lookupByUrl(url: String): PostLookup? {
        val ref =
            urlService.extractRef(url)
                ?: throw IllegalArgumentException("url is not a URL on this Bastion instance")
        val found = postService.findBySlug(ref.slug) ?: return null

        // Strict date validation: if the path pattern contains date placeholders,
        // the URL must match the post's published date, otherwise 404.
        if (!urlService.matchesDate(ref, found.publishedAt)) return null

        return toLookup(found)
    }

    fun tags(
        limitArg: Int?,
        offsetArg: Int?,
    ): TagPage {
        val limit = limitArg ?: 50
        val offset = offsetArg ?: 0
        validatePagination(limit, offset)

        val rows = postRepository.findTagsWithCounts(limit, offset)
        val total = postRepository.countDistinctTags()

        return TagPage(
            tags = rows.map { TagView(tag = it.getTag(), count = it.getCnt().toInt()) },
            limit = limit,
            offset = offset,
            total = total,
        )
    }

    fun syndicationLinks(postId: UUID): List<SyndicationLinkView> = postSyndicationService.findLinksByPostId(postId).map(::toLinkView)

    /** Batched [syndicationLinks] for feeds. Posts without links are absent from the map. */
    fun syndicationLinksByPostIds(postIds: Collection<UUID>): Map<UUID, List<SyndicationLinkView>> =
        postSyndicationService.findLinksByPostIds(postIds).mapValues { (_, links) -> links.map(::toLinkView) }

    private fun toLinkView(link: PostSyndication): SyndicationLinkView =
        SyndicationLinkView(uid = link.targetUid, name = link.targetName, url = link.syndicatedUrl)

    private fun toLookup(found: Post): PostLookup? =
        when {
            found.deleted && found.isPublicContent -> {
                PostLookup.Gone(
                    slug = found.slug,
                    url = requireNotNull(runCatching { urlService.generatePostUrl(found) }.getOrNull()) { "post ${found.id} missing url" },
                    published = requireNotNull(found.post.firstText("published")) { "post ${found.id} missing published" },
                )
            }

            found.publiclyReachable -> {
                PostLookup.Found(toView(found))
            }

            else -> {
                null
            }
        }

    private fun toView(post: Post): PostView =
        PostView(
            id = post.id,
            slug = post.slug,
            url = urlService.generatePostUrl(post),
            h = post.h,
            type = post.type,
            published = requireNotNull(post.post.firstText("published")) { "post ${post.id} missing published" },
            updated = requireNotNull(post.post.firstText("updated")) { "post ${post.id} missing updated" },
            name = post.post.firstText("name"),
            summary = post.post.texts("summary"),
            content = post.post.texts("content"),
            contentHtml = post.post.htmls("content"),
            category = post.post.texts("category"),
            properties = post.post.properties,
        )

    private fun validatePagination(
        limit: Int,
        offset: Int,
    ) {
        require(limit in 1..PostService.MAX_PAGE_SIZE) {
            "limit must be an integer between 1 and ${PostService.MAX_PAGE_SIZE}"
        }
        require(offset >= 0) { "offset must be a non-negative integer" }
        require(offset % limit == 0) { "offset must be a multiple of limit ($offset % $limit != 0)" }
    }

    private fun resolveDateRange(
        year: Int?,
        month: Int?,
        day: Int?,
    ): Pair<Instant, Instant>? {
        if (year == null && month == null && day == null) return null

        require(year != null) { "year is required when month or day is provided" }
        require(year in 1..9999) { "year must be between 1 and 9999" }

        if (day != null) {
            require(month != null) { "month is required when day is provided" }
        }

        if (month != null) {
            require(month in 1..12) { "month must be between 1 and 12" }
        }

        if (day != null) {
            val maxDay = YearMonth.of(year, month!!).lengthOfMonth()
            require(day in 1..maxDay) { "day must be between 1 and $maxDay for $year-${month.toString().padStart(2, '0')}" }
        }

        val start =
            when {
                day != null -> ZonedDateTime.of(year, month!!, day, 0, 0, 0, 0, zone)
                month != null -> ZonedDateTime.of(year, month, 1, 0, 0, 0, 0, zone)
                else -> ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, zone)
            }
        val end =
            when {
                day != null -> start.plusDays(1)
                month != null -> start.plusMonths(1)
                else -> start.plusYears(1)
            }

        return start.toInstant() to end.toInstant()
    }

    private fun parseMf2Types(raw: List<String>?): List<PostMf2Type>? =
        parseCsv(raw)?.map { value ->
            val normalized = value.lowercase().let { if (it.startsWith("h-")) it else "h-$it" }
            try {
                PostMf2Type.valueOf(normalized.uppercase().replace('-', '_'))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("unknown h type: $value")
            }
        }

    private fun parsePostTypes(raw: List<String>?): List<String>? =
        parseCsv(raw)?.map { value ->
            val normalized = value.lowercase()
            if (!postTypeCatalog.isKnownType(normalized)) {
                throw IllegalArgumentException("unknown post type: $value")
            }
            normalized
        }

    private fun parseCsv(raw: List<String>?): List<String>? =
        raw
            ?.flatMap { it.split(",") }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
}
