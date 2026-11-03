package dev.jacobandersen.bastion.post.internal.repository

import dev.jacobandersen.bastion.post.internal.entity.PostEntity
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface PostRepository :
    JpaRepository<PostEntity, UUID>,
    JpaSpecificationExecutor<PostEntity> {
    fun existsBySlug(slug: String): Boolean

    fun findBySlug(slug: String): PostEntity?

    fun findAllByOrderByIdAsc(pageable: Pageable): List<PostEntity>

    fun findByIdGreaterThanOrderByIdAsc(
        id: UUID,
        pageable: Pageable,
    ): List<PostEntity>

    fun findByStatusAndVisibilityAndDeletedFalse(
        status: PostStatus,
        visibility: PostVisibility,
        page: Pageable,
    ): Page<PostEntity>

    fun findByStatusAndVisibilityAndDeletedFalseAndTypeIn(
        status: PostStatus,
        visibility: PostVisibility,
        types: Collection<String>,
        page: Pageable,
    ): Page<PostEntity>

    fun findByStatusAndVisibilityAndDeletedFalseAndCreatedAtUtcGreaterThanEqualAndCreatedAtUtcLessThan(
        status: PostStatus,
        visibility: PostVisibility,
        from: Instant,
        to: Instant,
        page: Pageable,
    ): Page<PostEntity>

    fun findByStatusAndVisibilityAndDeletedFalseAndTypeInAndCreatedAtUtcGreaterThanEqualAndCreatedAtUtcLessThan(
        status: PostStatus,
        visibility: PostVisibility,
        types: Collection<String>,
        from: Instant,
        to: Instant,
        page: Pageable,
    ): Page<PostEntity>

    @Query(
        value = """
            select unnest(categories) as tag, count(*) as cnt
            from posts
            where status = 'PUBLISHED' and visibility = 'PUBLIC' and deleted = false
              and categories <> '{}'::text[]
            group by tag
            order by cnt desc, tag asc
            limit :limit offset :offset
        """,
        nativeQuery = true,
    )
    fun findTagsWithCounts(
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<TagCountRow>

    @Query(
        value = """
            select count(*) from (
                select distinct unnest(categories) as tag
                from posts
                where status = 'PUBLISHED' and visibility = 'PUBLIC' and deleted = false
                  and categories <> '{}'::text[]
            ) s
        """,
        nativeQuery = true,
    )
    fun countDistinctTags(): Long

    interface TagCountRow {
        fun getTag(): String

        fun getCnt(): Long
    }
}
