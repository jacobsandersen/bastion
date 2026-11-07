package dev.jacobandersen.bastion.post.internal.syndication

import dev.jacobandersen.bastion.post.internal.syndication.SyndicationAction
import dev.jacobandersen.bastion.post.internal.syndication.SyndicationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class SyndicationConfigTest {
    private fun target(
        uid: String,
        actions: Set<SyndicationAction> = setOf(SyndicationAction.CREATE, SyndicationAction.DELETE),
    ) = SyndicationConfig.Target(uid = uid, name = uid, endpoint = "https://example.com/micropub", actions = actions)

    @Test
    fun `defaults to create and delete actions`() {
        assertEquals(setOf(SyndicationAction.CREATE, SyndicationAction.DELETE), target("bridgy").actions)
    }

    @Test
    fun `binds lowercase action values and a token`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "bastion.micropub.syndication.targets[0].uid" to "bridgy",
                    "bastion.micropub.syndication.targets[0].name" to "Bridgy",
                    "bastion.micropub.syndication.targets[0].endpoint" to "https://brid.gy/micropub",
                    "bastion.micropub.syndication.targets[0].token" to "secret",
                    "bastion.micropub.syndication.targets[0].actions[0]" to "create",
                    "bastion.micropub.syndication.targets[0].actions[1]" to "delete",
                ),
            )

        val config =
            Binder(source)
                .bind("bastion.micropub.syndication", Bindable.of(SyndicationConfig::class.java))
                .get()

        val target = config.targets.single()
        assertEquals("bridgy", target.uid)
        assertEquals("Bridgy", target.name)
        assertEquals("https://brid.gy/micropub", target.endpoint)
        assertEquals("secret", target.token)
        assertEquals(setOf(SyndicationAction.CREATE, SyndicationAction.DELETE), target.actions)
    }

    @Test
    fun `binds empty target list`() {
        val config = SyndicationConfig()
        assertTrue(config.targets.isEmpty())
    }

    @Test
    fun `targetsSupporting filters by action and deduplicates uids`() {
        val config =
            SyndicationConfig(
                listOf(
                    target("bridgy"),
                    target("delete-only", actions = setOf(SyndicationAction.DELETE)),
                ),
            )

        assertEquals(
            listOf("bridgy"),
            config.targetsSupporting(listOf("bridgy", "bridgy"), SyndicationAction.CREATE).map { it.uid },
        )
        assertEquals(
            listOf("delete-only"),
            config.targetsSupporting(listOf("delete-only"), SyndicationAction.DELETE).map { it.uid },
        )
        assertTrue(config.targetsSupporting(listOf("delete-only"), SyndicationAction.CREATE).isEmpty())
        assertTrue(config.targetsSupporting(listOf("unknown"), SyndicationAction.CREATE).isEmpty())
    }

    @Test
    fun `targetByUid returns matching target or null`() {
        val config = SyndicationConfig(listOf(target("bridgy")))
        assertEquals("bridgy", config.targetByUid("bridgy")?.uid)
        assertEquals(null, config.targetByUid("missing"))
    }

    @Test
    fun `auto defaults to false and autoTargetUids lists auto targets`() {
        val auto = SyndicationConfig.Target(uid = "always", name = "Always", endpoint = "https://example.com/m", auto = true)
        assertEquals(false, target("bridgy").auto)
        assertEquals(listOf("always"), SyndicationConfig(listOf(target("bridgy"), auto)).autoTargetUids())
    }
}
