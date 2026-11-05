package dev.jacobandersen.bastion.post.internal.model

import dev.jacobandersen.bastion.post.events.PostFeedFacets
import dev.jacobandersen.bastion.post.internal.entity.PostEntity
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import dev.jacobandersen.microformats2.texts
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class Post(
    val id: UUID,
    val slug: String,
    val status: PostStatus,
    val visibility: PostVisibility,
    val deleted: Boolean = false,
    val h: String,
    val type: String? = null,
    val post: Mf2Object,
) {
    val publishedAt: OffsetDateTime?
        get() =
            (post.getFirstProperty("published") as? Mf2Value.String)
                ?.value
                ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    /**
     * Whether the post is published content that could be shown publicly,
     * regardless of a soft-delete tombstone (used to decide dispatch behavior
     * when toggling delete/undelete).
     */
    val isPublicContent: Boolean
        get() = status == PostStatus.PUBLISHED && visibility.canGetByUrl()

    /** Whether the post is currently publicly reachable (not deleted). */
    val publiclyReachable: Boolean
        get() = !deleted && isPublicContent

    /**
     * Whether the post appears in the public feed. Unlike [publiclyReachable],
     * this excludes UNLISTED posts, which are reachable by URL but are not part
     * of the feed topics that WebSub subscribers watch.
     */
    val isFeedVisible: Boolean
        get() = !deleted && status == PostStatus.PUBLISHED && visibility == PostVisibility.PUBLIC

    /** What decides which feeds this post appears in; see [PostFeedFacets]. */
    val feedFacets: PostFeedFacets
        get() =
            PostFeedFacets(
                type = type,
                tags =
                    post
                        .texts("category")
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct(),
                publishedYear = publishedAt?.withOffsetSameInstant(ZoneOffset.UTC)?.year,
            )

    /**
     * Map this domain Post to a PostEntity for persisting a new post.
     * `h`, `type`, `created_at_utc` and `updated_at_utc` are
     * database-generated columns, so they are intentionally not set here -
     * Postgres recomputes them from the `post` JSON on INSERT and Hibernate
     * refetches them after flush. Updates go through the managed entity
     * instead of building a detached one.
     */
    fun toEntity(): PostEntity =
        PostEntity(
            id = id,
            slug = slug,
            status = status,
            visibility = visibility,
            deleted = deleted,
            post = post,
            type = type,
        )
}
