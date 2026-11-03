package dev.jacobandersen.bastion.post.internal.store

import dev.jacobandersen.bastion.post.events.PostChangeType
import dev.jacobandersen.bastion.post.events.PostChangedEvent
import dev.jacobandersen.bastion.post.internal.entity.PostEntity
import dev.jacobandersen.bastion.post.internal.model.Post
import dev.jacobandersen.bastion.post.internal.repository.PostRepository
import dev.jacobandersen.bastion.post.internal.types.PostTypeCatalog
import dev.jacobandersen.bastion.post.types.PostMf2Type
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostTagFilter
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.bastion.post.types.mf2Type
import dev.jacobandersen.microformats2.Mf2Object
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PostService(
    val repository: PostRepository,
    private val postTimeService: PostTimeService,
    private val eventPublisher: ApplicationEventPublisher,
    private val entityManager: EntityManager,
    private val postTypeCatalog: PostTypeCatalog,
) {
    companion object {
        const val MAX_PAGE_SIZE = 100
    }

    @Transactional
    fun create(
        slug: String,
        status: PostStatus,
        visibility: PostVisibility,
        deleted: Boolean,
        post: Mf2Object,
    ): Post {
        val stamped = postTimeService.applyCreateTimestamps(post)
        val type = postTypeCatalog.discover(stamped)?.type
        val saved = repository.saveAndFlush(PostEntity(slug, status, visibility, deleted, stamped, type)).toDomain()
        eventPublisher.publishEvent(
            PostChangedEvent(
                postId = saved.id,
                changeType = PostChangeType.CREATED,
                wasFeedVisible = false,
                isFeedVisible = saved.isFeedVisible,
                occurredAt = Instant.now(),
            ),
        )
        return saved
    }

    @Transactional(readOnly = true)
    fun findBySlug(slug: String): Post? = repository.findBySlug(slug)?.toDomain()

    @Transactional(readOnly = true)
    fun findById(id: UUID): Post? = repository.findById(id).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    fun doesExistBySlug(slug: String): Boolean = repository.existsBySlug(slug)

    @Transactional(readOnly = true)
    fun deduplicateSlug(
        baseSlug: String,
        excludingSlug: String? = null,
    ): String {
        if (excludingSlug == baseSlug) return baseSlug

        var candidate = baseSlug
        var count = 2
        while (repository.existsBySlug(candidate)) {
            candidate = "$baseSlug-$count"
            count++
        }
        return candidate
    }

    @Transactional
    fun updatePost(
        post: Post,
        modified: Mf2Object,
    ): Post {
        val stamped = postTimeService.applyUpdateTimestamps(modified)

        val entity =
            repository
                .findById(post.id)
                .orElseThrow { IllegalArgumentException("Post not found for id ${post.id}") }
        val previous = entity.toDomain()
        entity.slug = post.slug
        entity.status = post.status
        entity.visibility = post.visibility
        entity.deleted = post.deleted
        entity.post = stamped
        entity.type = postTypeCatalog.discover(stamped)?.type

        repository.saveAndFlush(entity)
        entityManager.refresh(entity)
        val saved = entity.toDomain()
        val changeType =
            when {
                !previous.deleted && saved.deleted -> PostChangeType.DELETED
                previous.deleted && !saved.deleted -> PostChangeType.RESTORED
                else -> PostChangeType.UPDATED
            }
        eventPublisher.publishEvent(
            PostChangedEvent(
                postId = saved.id,
                changeType = changeType,
                wasFeedVisible = previous.isFeedVisible,
                isFeedVisible = saved.isFeedVisible,
                occurredAt = Instant.now(),
            ),
        )
        return saved
    }

    @Transactional(readOnly = true)
    fun findPublicPosts(
        limit: Int,
        offset: Int,
    ): List<Post> {
        require(offset % limit == 0) { "offset must be a multiple of limit" }

        val pageRequest = PageRequest.of(offset / limit, limit, Sort.by("createdAtUtc").descending())

        val results =
            repository.findByStatusAndVisibilityAndDeletedFalse(
                PostStatus.PUBLISHED,
                PostVisibility.PUBLIC,
                pageRequest,
            )

        return results.content.map { it.toDomain() }
    }

    /**
     * Published, public, non-deleted posts for the public feed, optionally
     * restricted by mf2 `type` (h-entry, h-card, ...), `type` and tags.
     * Filters within each list are OR, across lists are AND. For tags, the
     * `none` sentinel means an empty categories array.
     */
    @Transactional(readOnly = true)
    fun findFeedPosts(
        mf2Types: Collection<PostMf2Type>?,
        types: Collection<String>?,
        limit: Int,
        offset: Int,
    ): Page<Post> = findFeedPosts(mf2Types, types, limit, offset, null, null, null)

    @Transactional(readOnly = true)
    fun findFeedPosts(
        mf2Types: Collection<PostMf2Type>?,
        types: Collection<String>?,
        limit: Int,
        offset: Int,
        from: Instant?,
        toExclusive: Instant?,
    ): Page<Post> = findFeedPosts(mf2Types, types, limit, offset, from, toExclusive, null)

    @Transactional(readOnly = true)
    fun findFeedPosts(
        mf2Types: Collection<PostMf2Type>?,
        types: Collection<String>?,
        limit: Int,
        offset: Int,
        from: Instant?,
        toExclusive: Instant?,
        tagFilter: PostTagFilter?,
    ): Page<Post> {
        require(offset % limit == 0) { "offset must be a multiple of limit" }
        require((from == null) == (toExclusive == null)) { "both from and toExclusive must be provided together" }

        val pageRequest = PageRequest.of(offset / limit, limit, Sort.by("createdAtUtc").descending())

        val mf2TypeStrings = mf2Types?.map { it.mf2Type() }

        val spec =
            buildFeedSpecification(
                mf2TypeStrings,
                types,
                from,
                toExclusive,
                tagFilter,
            )
        return repository.findAll(spec, pageRequest).map { it.toDomain() }
    }

    private fun buildFeedSpecification(
        mf2Types: Collection<String>?,
        types: Collection<String>?,
        from: Instant?,
        toExclusive: Instant?,
        tagFilter: PostTagFilter? = null,
    ): Specification<PostEntity> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()

            predicates += cb.equal(root.get<PostStatus>("status"), PostStatus.PUBLISHED)
            predicates += cb.equal(root.get<PostVisibility>("visibility"), PostVisibility.PUBLIC)
            predicates += cb.isFalse(root.get("deleted"))

            if (!mf2Types.isNullOrEmpty()) {
                predicates += root.get<String>("h").`in`(mf2Types)
            }
            if (!types.isNullOrEmpty()) {
                predicates += root.get<String>("type").`in`(types)
            }

            if (tagFilter != null) {
                val categories = root.get<Array<String>>("categories")
                val ordinaryTagPredicate =
                    if (tagFilter.tags.isNotEmpty()) {
                        cb.isTrue(
                            cb.function(
                                "categories_overlap",
                                Boolean::class.java,
                                categories,
                                cb.literal(tagFilter.tags.toTypedArray()),
                            ),
                        )
                    } else {
                        null
                    }
                val untaggedPredicate =
                    if (tagFilter.includeUntagged) {
                        cb.isTrue(
                            cb.function(
                                "categories_empty",
                                Boolean::class.java,
                                categories,
                            ),
                        )
                    } else {
                        null
                    }

                when {
                    untaggedPredicate != null && ordinaryTagPredicate != null -> {
                        predicates += cb.or(untaggedPredicate, ordinaryTagPredicate)
                    }

                    untaggedPredicate != null -> {
                        predicates += untaggedPredicate
                    }

                    ordinaryTagPredicate != null -> {
                        predicates += ordinaryTagPredicate
                    }
                }
            }

            if (from != null && toExclusive != null) {
                predicates += cb.greaterThanOrEqualTo(root.get("createdAtUtc"), from)
                predicates += cb.lessThan(root.get("createdAtUtc"), toExclusive)
            }

            cb.and(*predicates.toTypedArray())
        }
}
