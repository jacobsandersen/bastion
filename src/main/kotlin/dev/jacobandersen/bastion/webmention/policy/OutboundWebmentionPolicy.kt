package dev.jacobandersen.bastion.webmention.policy

/**
 * Lets another module veto outbound webmentions to a target. Bastion sends
 * nothing to a suppressed target: no new webmentions, no refreshes after an
 * edit (including Salmention follow-ups), and no retractions, since each of
 * those would tell the target's owner something happened. Webmentions already
 * delivered are left as they are. Suppression is evaluated at reconcile time
 * and again just before each send, so a job queued before the policy changed
 * is also stopped.
 *
 * Implementations must be cheap and side-effect free, and must not throw.
 */
fun interface OutboundWebmentionPolicy {
    fun isSuppressed(targetUrl: String): Boolean
}
