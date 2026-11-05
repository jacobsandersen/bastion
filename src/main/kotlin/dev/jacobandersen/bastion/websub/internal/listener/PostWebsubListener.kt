package dev.jacobandersen.bastion.websub.internal.listener

import dev.jacobandersen.bastion.post.events.PostChangedEvent
import dev.jacobandersen.bastion.websub.internal.WebsubPublisher
import dev.jacobandersen.bastion.websub.internal.WebsubTopicResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.time.Year
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

/**
 * Publishes the affected WebSub topics when the public feed changes.
 *
 * The WebSub specification requires publishers to inform their hubs when a
 * topic has been updated. A post belongs to several frontend-rendered feeds
 * (main, type, tag, uncategorized, year), so a post that enters, leaves or is
 * edited within the feed pings each feed it is or was in. Draft and unlisted
 * edits do not change any feed.
 */
@Component
class PostWebsubListener(
    private val websubPublisher: WebsubPublisher,
    private val topicResolver: WebsubTopicResolver,
) {
    @ApplicationModuleListener
    fun on(event: PostChangedEvent) {
        if (!event.wasFeedVisible && !event.isFeedVisible) return

        val topics = topicResolver.resolve(event, Year.now(ZoneOffset.UTC).value)
        logger.info { "Publishing WebSub for post ${event.postId} (${event.changeType}): $topics" }
        websubPublisher.publish(topics)
    }
}
