package dev.jacobandersen.bastion.webmention.internal.service

import dev.jacobandersen.bastion.post.query.PostStateView
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.bastion.url.ContentUrlMatcher
import dev.jacobandersen.bastion.webmention.internal.WebmentionService
import dev.jacobandersen.bastion.webmention.internal.config.WebmentionConfig
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionNotification
import dev.jacobandersen.bastion.webmention.internal.data.domain.WebmentionState
import dev.jacobandersen.bastion.webmention.internal.data.service.EndpointCacheResult
import dev.jacobandersen.bastion.webmention.internal.data.service.WebmentionEndpointCacheService
import dev.jacobandersen.bastion.webmention.internal.data.service.WebmentionNotificationService
import dev.jacobandersen.bastion.webmention.internal.http.EndpointDiscovery
import dev.jacobandersen.bastion.webmention.internal.http.SendWebmentionResult
import dev.jacobandersen.bastion.webmention.internal.http.WebmentionHttpClient
import dev.jacobandersen.bastion.webmention.policy.OutboundWebmentionPolicy
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import java.time.Instant
import java.util.UUID

class WebmentionServiceTest {
    private val jobScheduler = mock(JobScheduler::class.java)
    private val notificationService = mock(WebmentionNotificationService::class.java)
    private val endpointCacheService = mock(WebmentionEndpointCacheService::class.java)
    private val httpClient = mock(WebmentionHttpClient::class.java)
    private val config = WebmentionConfig()
    private val postId = UUID.randomUUID()
    private val source = "https://bastion.test/2026/09/07/post"
    private val target = "https://example.com/a"
    private val contentUrlMatcher =
        mock(ContentUrlMatcher::class.java).apply {
            `when`(isOwnContentUrl(org.mockito.ArgumentMatchers.anyString())).thenReturn(false)
        }

    private val service =
        WebmentionService(jobScheduler, notificationService, endpointCacheService, httpClient, config, contentUrlMatcher)

    private fun entry(content: String): Mf2Object =
        Mf2Object(
            type = listOf("h-entry"),
            properties = mapOf("content" to listOf(Mf2Value.String(content))),
            children = null,
        )

    private fun post(
        content: String = "",
        status: PostStatus = PostStatus.PUBLISHED,
        visibility: PostVisibility = PostVisibility.PUBLIC,
        deleted: Boolean = false,
    ) = PostStateView(
        id = postId,
        slug = "post",
        url = source,
        h = "h-entry",
        type = null,
        post = entry(content),
        deleted = deleted,
        publiclyReachable = !deleted && status == PostStatus.PUBLISHED && visibility.canGetByUrl(),
        isFeedVisible = !deleted && status == PostStatus.PUBLISHED && visibility == PostVisibility.PUBLIC,
    )

    private fun notification(
        targetUrl: String,
        delivered: Boolean = true,
        sourceUrl: String = source,
        notificationPostId: UUID? = postId,
    ) = WebmentionNotification(
        id = UUID.randomUUID(),
        postId = notificationPostId,
        sourceUrl = sourceUrl,
        targetUrl = targetUrl,
        state = WebmentionState.ACTIVE,
        delivered = delivered,
        createdAtUtc = Instant.now(),
        updatedAtUtc = Instant.now(),
    )

    @Test
    fun `reconcile enqueues sends for unknown targets`() {
        service.reconcile(post(content = "see $target"), refresh = true)

        verify(notificationService, times(1)).setActivePending(source, target, postId)
    }

    @Test
    fun `reconcile re-sends active delivered targets when refreshing`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(target)))

        service.reconcile(post(content = "see $target"), refresh = true)

        verify(notificationService, times(1)).setActivePending(source, target, postId)
    }

    @Test
    fun `reconcile leaves active delivered targets alone without refresh`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(target)))

        service.reconcile(post(content = "see $target"), refresh = false)

        verify(notificationService, never()).setActivePending(source, target, postId)
    }

    @Test
    fun `reconcile retracts removed delivered targets`() {
        val removed = "https://example.com/c"
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(removed)))

        service.reconcile(post(content = "see $target"), refresh = true)

        verify(notificationService, times(1)).markInactivePendingRetraction(source, removed)
    }

    @Test
    fun `reconcile silently inactivates removed undelivered targets`() {
        val removed = "https://example.com/c"
        `when`(notificationService.notificationsByPostId(postId))
            .thenReturn(listOf(notification(removed, delivered = false)))

        service.reconcile(post(content = "see $target"), refresh = true)

        verify(notificationService, times(1)).markInactiveSilent(source, removed)
    }

    @Test
    fun `reconcile retracts delivered targets for a deleted post`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(target)))

        service.reconcile(post(content = "see $target", deleted = true), refresh = false)

        verify(notificationService, times(1)).markInactivePendingRetraction(source, target)
        verify(notificationService, never()).setActivePending(source, target, postId)
    }

    @Test
    fun `reconcile silently inactivates targets for a private post`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(target)))

        service.reconcile(
            post(content = "see $target", visibility = PostVisibility.PRIVATE),
            refresh = true,
        )

        verify(notificationService, times(1)).markInactiveSilent(source, target)
        verify(notificationService, never()).markInactivePendingRetraction(source, target)
    }

    @Test
    fun `reconcile retracts notifications at an old source url`() {
        val oldSource = "https://bastion.test/2026/09/06/old"
        `when`(notificationService.notificationsByPostId(postId))
            .thenReturn(listOf(notification(target, sourceUrl = oldSource)))

        service.reconcile(post(content = "see $target"), refresh = true)

        verify(notificationService, times(1)).markInactivePendingRetraction(oldSource, target)
        verify(notificationService, times(1)).setActivePending(source, target, postId)
    }

    @Test
    fun `retryDueWebmentions enqueues sends for due notifications`() {
        val due = notification("https://example.com/a")
        `when`(notificationService.dueForRetry(any(), any())).thenReturn(listOf(due))

        service.retryDueWebmentions()

        verify(notificationService, times(1))
            .dueForRetry(any(), org.mockito.kotlin.eq(listOf(WebmentionState.ACTIVE, WebmentionState.INACTIVE)))
    }

    @Test
    fun `sendWebmention skips blocked targets`() {
        service.sendWebmention(source, "http://127.0.0.1/internal")

        verify(
            notificationService,
            times(1),
        ).recordFailure(source, "http://127.0.0.1/internal", null, "target URL resolves to a blocked address")
        verify(notificationService, times(1)).scheduleNextAttempt(source, "http://127.0.0.1/internal", null)
        verify(
            httpClient,
            never(),
        ).sendWebmention(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    @Test
    fun `sendWebmention records terminal failure when no endpoint is advertised`() {
        `when`(endpointCacheService.lookup(org.mockito.ArgumentMatchers.anyString(), any()))
            .thenReturn(EndpointCacheResult.Miss)
        `when`(httpClient.discoverWebmentionEndpoint(target)).thenReturn(EndpointDiscovery(null, null, null))

        service.sendWebmention(source, target)

        verify(notificationService, times(1)).recordFailure(source, target, null, "no webmention endpoint advertised")
        verify(notificationService, times(1)).scheduleNextAttempt(source, target, null)
    }

    @Test
    fun `sendWebmention uses a fresh cached endpoint`() {
        `when`(endpointCacheService.lookup(org.mockito.kotlin.eq(target), any()))
            .thenReturn(EndpointCacheResult.Fresh("https://example.com/wm"))
        `when`(httpClient.sendWebmention(source, target, "https://example.com/wm"))
            .thenReturn(SendWebmentionResult.Success(202))

        service.sendWebmention(source, target)

        verify(notificationService, times(1)).recordSuccess(source, target, 202)
        verify(httpClient, never()).discoverWebmentionEndpoint(target)
    }

    @Test
    fun `sendWebmention schedules retry with backoff for transient failures`() {
        `when`(endpointCacheService.lookup(org.mockito.kotlin.eq(target), any()))
            .thenReturn(EndpointCacheResult.Fresh("https://example.com/wm"))
        `when`(httpClient.sendWebmention(source, target, "https://example.com/wm"))
            .thenReturn(SendWebmentionResult.Failure(503, "HTTP 503", retryable = true))
        `when`(notificationService.recordFailure(source, target, 503, "HTTP 503")).thenReturn(1)

        service.sendWebmention(source, target)

        verify(notificationService, times(1)).scheduleNextAttempt(
            org.mockito.kotlin.eq(source),
            org.mockito.kotlin.eq(target),
            org.mockito.kotlin.check { next ->
                assertEquals(
                    config.backoffBaseSeconds.toDouble(),
                    (next.epochSecond - Instant.now().epochSecond).toDouble(),
                    2.0,
                )
            },
        )
    }

    @Test
    fun `sendWebmention stops retrying after the max attempts`() {
        `when`(endpointCacheService.lookup(org.mockito.kotlin.eq(target), any()))
            .thenReturn(EndpointCacheResult.Fresh("https://example.com/wm"))
        `when`(httpClient.sendWebmention(source, target, "https://example.com/wm"))
            .thenReturn(SendWebmentionResult.Failure(503, "HTTP 503", retryable = true))
        `when`(notificationService.recordFailure(source, target, 503, "HTTP 503")).thenReturn(config.maxAttempts)

        service.sendWebmention(source, target)

        verify(notificationService, times(1)).scheduleNextAttempt(source, target, null)
    }

    @Test
    fun `sendWebmention does not retry non-retryable failures`() {
        `when`(endpointCacheService.lookup(org.mockito.kotlin.eq(target), any()))
            .thenReturn(EndpointCacheResult.Fresh("https://example.com/wm"))
        `when`(httpClient.sendWebmention(source, target, "https://example.com/wm"))
            .thenReturn(SendWebmentionResult.Failure(400, "HTTP 400", retryable = false))
        `when`(notificationService.recordFailure(source, target, 400, "HTTP 400")).thenReturn(1)

        service.sendWebmention(source, target)

        verify(notificationService, times(1)).scheduleNextAttempt(source, target, null)
    }

    @Test
    fun `reconcile skips targets on the own content domain`() {
        val own = "https://test.jacobandersen.dev/2026/01/01/other"
        `when`(contentUrlMatcher.isOwnContentUrl(own)).thenReturn(true)

        service.reconcile(post(content = "see $target and $own"), refresh = true)

        verify(notificationService, times(1)).setActivePending(source, target, postId)
        verify(notificationService, never()).setActivePending(source, own, postId)
    }

    @Test
    fun `sendWebmention records terminal failure for self targets without sending`() {
        val own = "https://test.jacobandersen.dev/2026/01/01/other"
        `when`(contentUrlMatcher.isOwnContentUrl(own)).thenReturn(true)

        service.sendWebmention(source, own)

        verify(notificationService, times(1)).recordFailure(source, own, null, "self webmention skipped")
        verify(notificationService, times(1)).scheduleNextAttempt(source, own, null)
        verify(
            httpClient,
            never(),
        ).sendWebmention(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    // --- outbound policy (blocked users) -----------------------------------------------------------------------------

    private val suppressedHosts = mutableSetOf<String>()
    private val suppressed = "https://blocked.example/post"

    private val policed =
        WebmentionService(
            jobScheduler,
            notificationService,
            endpointCacheService,
            httpClient,
            config,
            contentUrlMatcher,
            listOf(OutboundWebmentionPolicy { url -> suppressedHosts.any { url.contains("//$it/") } }),
        )

    @BeforeEach
    fun suppressBlockedExample() {
        suppressedHosts.clear()
        suppressedHosts += "blocked.example"
    }

    @Test
    fun `policy keeps new suppressed targets from being recorded or sent`() {
        policed.reconcile(post(content = "see $target and $suppressed"), refresh = true)

        verify(notificationService, times(1)).setActivePending(source, target, postId)
        verify(notificationService, never()).setActivePending(source, suppressed, postId)
    }

    @Test
    fun `policy leaves a delivered webmention to a suppressed target alone on refresh`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(suppressed)))

        policed.reconcile(post(content = "see $suppressed"), refresh = true)

        verify(notificationService, never()).setActivePending(source, suppressed, postId)
        verify(notificationService, never()).markInactivePendingRetraction(source, suppressed)
        verify(notificationService, never()).markInactiveSilent(source, suppressed)
        verifyNoInteractions(jobScheduler)
    }

    @Test
    fun `policy never retracts from a suppressed target when the link is removed`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(suppressed)))

        policed.reconcile(post(content = "see $target"), refresh = true)

        verify(notificationService, never()).markInactivePendingRetraction(source, suppressed)
        verify(notificationService, times(1)).markInactiveSilent(source, suppressed)
        verify(notificationService, times(1)).setActivePending(source, target, postId)
    }

    @Test
    fun `policy never retracts from a suppressed target when the post is deleted`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(suppressed)))

        policed.reconcile(post(content = "see $suppressed", deleted = true), refresh = false)

        verify(notificationService, never()).markInactivePendingRetraction(source, suppressed)
        verify(notificationService, times(1)).markInactiveSilent(source, suppressed)
        verifyNoInteractions(jobScheduler)
    }

    @Test
    fun `policy stops an undelivered pending webmention to a suppressed target`() {
        `when`(notificationService.notificationsByPostId(postId))
            .thenReturn(listOf(notification(suppressed, delivered = false)))

        policed.reconcile(post(content = "see $suppressed"), refresh = false)

        verify(notificationService, times(1)).markInactiveSilent(source, suppressed)
        verify(notificationService, never()).setActivePending(source, suppressed, postId)
        verifyNoInteractions(jobScheduler)
    }

    @Test
    fun `policy leaves other targets of the same post untouched`() {
        `when`(notificationService.notificationsByPostId(postId))
            .thenReturn(listOf(notification(suppressed), notification(target)))

        policed.reconcile(post(content = "see $target and $suppressed"), refresh = true)

        verify(notificationService, times(1)).setActivePending(source, target, postId)
        verify(notificationService, never()).setActivePending(source, suppressed, postId)
    }

    @Test
    fun `policy resumes sending once the target is no longer suppressed`() {
        `when`(notificationService.notificationsByPostId(postId)).thenReturn(listOf(notification(suppressed)))
        policed.reconcile(post(content = "see $suppressed"), refresh = true)
        verify(notificationService, never()).setActivePending(source, suppressed, postId)

        suppressedHosts.clear()
        policed.reconcile(post(content = "see $suppressed"), refresh = true)

        verify(notificationService, times(1)).setActivePending(source, suppressed, postId)
    }

    @Test
    fun `policy stops a send that was queued before the target became suppressed`() {
        policed.sendWebmention(source, suppressed)

        verifyNoInteractions(httpClient)
        verifyNoInteractions(endpointCacheService)
        verify(notificationService, times(1)).recordFailure(source, suppressed, null, "target suppressed by policy")
        verify(notificationService, times(1)).scheduleNextAttempt(source, suppressed, null)
    }

    @Test
    fun `policy does not affect sends to other targets`() {
        `when`(endpointCacheService.lookup(org.mockito.kotlin.eq(target), any()))
            .thenReturn(EndpointCacheResult.Fresh("https://example.com/webmention"))
        `when`(httpClient.sendWebmention(source, target, "https://example.com/webmention"))
            .thenReturn(SendWebmentionResult.Success(202))

        policed.sendWebmention(source, target)

        verify(notificationService, times(1)).recordSuccess(source, target, 202)
    }
}
