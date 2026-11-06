package dev.jacobandersen.bastion.microsub.internal.moderation

import dev.jacobandersen.bastion.microsub.internal.jf2.MicrosubUrls
import dev.jacobandersen.bastion.webmention.policy.OutboundWebmentionPolicy
import org.springframework.stereotype.Component

/**
 * Keeps Bastion from sending webmentions to blocked users. The spec says a
 * server SHOULD NOT produce any side effect that would notify a blocked user
 * and names Webmention and Salmention follow-ups, but it does not say how a
 * block (a profile URL, optionally per channel) maps to a webmention target
 * (a post URL). These are the interpretations, chosen to err toward silence:
 *
 * - A target is suppressed when it is on the same site (host and non-default
 *   port, either scheme) as a blocked profile URL. Matching the exact URL
 *   would almost never fire. Tradeoff: on a shared host such as
 *   `example.com/~alice` and `example.com/~bob`, blocking one blocks both.
 * - A block in any channel, or `global`, suppresses. A webmention belongs to
 *   no channel, so a channel-scoped block cannot be honored selectively.
 * - Nothing already delivered is retracted, because a retraction is itself a
 *   webmention. Unblocking resumes sending the next time the post is reconciled
 *   (edited, restored and so on), not immediately.
 */
@Component
class BlockWebmentionPolicy(
    private val moderation: ModerationRepository,
) : OutboundWebmentionPolicy {
    override fun isSuppressed(targetUrl: String): Boolean {
        val site = MicrosubUrls.site(targetUrl) ?: return false
        return moderation.blockedUrls().any { MicrosubUrls.site(it) == site }
    }
}
