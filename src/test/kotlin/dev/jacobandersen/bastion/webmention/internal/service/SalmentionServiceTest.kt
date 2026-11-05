package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.post.query.PostQueryService
import dev.jacobandersen.bastion.post.query.PostStateView
import dev.jacobandersen.bastion.webmention.internal.WebmentionService
import dev.jacobandersen.microformats2.Mf2Object
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class SalmentionServiceTest {
    private val postQueryService = mock(PostQueryService::class.java)
    private val webmentionService = mock(WebmentionService::class.java)
    private val service = SalmentionService(postQueryService, webmentionService)
    private val postId = UUID.randomUUID()

    private fun post(reachable: Boolean) =
        PostStateView(
            id = postId,
            slug = "post",
            url = "https://bastion.test/2026/01/01/post",
            h = "h-entry",
            type = null,
            post = Mf2Object(type = listOf("h-entry")),
            deleted = false,
            publiclyReachable = reachable,
            isFeedVisible = reachable,
        )

    @Test
    fun `re-sends to delivered targets of a reachable post`() {
        val post = post(reachable = true)
        `when`(postQueryService.findById(postId)).thenReturn(post)

        service.resendAfterResponseChange(postId)

        verify(webmentionService).reconcile(post, true)
    }

    @Test
    fun `does nothing for an unreachable post`() {
        `when`(postQueryService.findById(postId)).thenReturn(post(reachable = false))

        service.resendAfterResponseChange(postId)

        verifyNoInteractions(webmentionService)
    }

    @Test
    fun `does nothing for a missing post`() {
        service.resendAfterResponseChange(postId)

        verifyNoInteractions(webmentionService)
    }
}
