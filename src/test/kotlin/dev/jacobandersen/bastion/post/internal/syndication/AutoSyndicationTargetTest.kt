package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.post.commands.CreatePostCommand
import dev.jacobandersen.bastion.post.commands.PostCommandService
import dev.jacobandersen.bastion.post.commands.PostPatch
import dev.jacobandersen.bastion.post.commands.SyndicationTargetChange
import dev.jacobandersen.bastion.post.commands.UpdatePostCommand
import dev.jacobandersen.bastion.post.syndication.SyndicationTargetQueryService
import dev.jacobandersen.bastion.post.types.PostStatus
import dev.jacobandersen.bastion.post.types.PostVisibility
import dev.jacobandersen.microformats2.Mf2Object
import dev.jacobandersen.microformats2.Mf2Value
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
        "bastion.micropub.syndication.targets[0].uid=manual",
        "bastion.micropub.syndication.targets[0].name=Manual",
        "bastion.micropub.syndication.targets[0].endpoint=https://manual.example/micropub",
        "bastion.micropub.syndication.targets[1].uid=always",
        "bastion.micropub.syndication.targets[1].name=Always",
        "bastion.micropub.syndication.targets[1].endpoint=https://always.example/micropub",
        "bastion.micropub.syndication.targets[1].auto=true",
    ],
)
class AutoSyndicationTargetTest {
    @Autowired
    lateinit var postCommandService: PostCommandService

    @Autowired
    lateinit var postSyndicationService: PostSyndicationService

    @Autowired
    lateinit var targetQueryService: SyndicationTargetQueryService

    @MockitoBean
    lateinit var jobScheduler: JobScheduler

    @MockitoBean
    lateinit var httpClient: SyndicationHttpClient

    private fun entry() =
        Mf2Object(
            type = listOf("h-entry"),
            properties = mutableMapOf("content" to listOf(Mf2Value.String("body"))),
            children = null,
        )

    private fun create(uids: List<String> = emptyList()) =
        postCommandService.create(
            CreatePostCommand(
                post = entry(),
                requestedSlug = "auto-${System.nanoTime()}",
                status = PostStatus.PUBLISHED,
                visibility = PostVisibility.PUBLIC,
                syndicationTargetUids = uids,
            ),
        )

    private fun desired(postId: UUID) =
        postSyndicationService
            .findByPostId(postId)
            .filter { it.desired }
            .map { it.targetUid }
            .toSet()

    @Test
    fun `auto targets are hidden from advertised targets`() {
        assertEquals(listOf("manual"), targetQueryService.targets().map { it.uid })
    }

    @Test
    fun `new posts get auto targets without mp-syndicate-to`() {
        assertEquals(setOf("always"), desired(create().id))
    }

    @Test
    fun `requested targets are combined with auto targets`() {
        assertEquals(setOf("manual", "always"), desired(create(listOf("manual")).id))
    }

    @Test
    fun `replace and removeAll cannot drop an auto target`() {
        val created = create(listOf("manual"))

        postCommandService.update(
            UpdatePostCommand(
                url = created.url,
                patch = PostPatch(),
                requestedSlug = null,
                status = null,
                visibility = null,
                syndicationTargets = SyndicationTargetChange(replace = emptyList()),
            ),
        )
        assertTrue("always" in desired(created.id))
        assertTrue("manual" !in desired(created.id))

        postCommandService.update(
            UpdatePostCommand(
                url = created.url,
                patch = PostPatch(),
                requestedSlug = null,
                status = null,
                visibility = null,
                syndicationTargets = SyndicationTargetChange(removeAll = true),
            ),
        )
        assertEquals(setOf("always"), desired(created.id))
    }
}
