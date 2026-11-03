package dev.jacobandersen.bastion.post.read

import dev.jacobandersen.microformats2.Mf2Value
import java.util.UUID

data class PostView(
    val id: UUID,
    val slug: String,
    val url: String,
    val h: String,
    val type: String?,
    val published: String,
    val updated: String,
    val name: String?,
    val summary: List<String>,
    val content: List<String>,
    val contentHtml: List<String>,
    val category: List<String>,
    val properties: Map<String, List<Mf2Value>>,
)

sealed interface PostLookup {
    data class Found(
        val post: PostView,
    ) : PostLookup

    data class Gone(
        val slug: String,
        val url: String,
        val published: String,
    ) : PostLookup
}

data class PostFeedQuery(
    val h: List<String>? = null,
    val type: List<String>? = null,
    val tag: List<String>? = null,
    val limit: Int = 10,
    val offset: Int = 0,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

data class PostFeedPage(
    val posts: List<PostView>,
    val total: Long,
)

data class TagView(
    val tag: String,
    val count: Int,
)

data class TagPage(
    val tags: List<TagView>,
    val limit: Int,
    val offset: Int,
    val total: Long,
)

data class SyndicationLinkView(
    val uid: String,
    val name: String,
    val url: String,
)
