package dev.jacobandersen.bastion.microsub

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Microsub server (https://indieweb.org/Microsub-spec): follows feeds for the
 * configured Bastion identity and serves channels and timelines to reader
 * clients. No public surface. It contributes an `OutboundWebmentionPolicy` so
 * blocked users are not sent webmentions.
 */
@PackageInfo
@ApplicationModule(
    type = ApplicationModule.Type.CLOSED,
    allowedDependencies = [
        "indieauth::indieauth-token-query", "indieauth::indieauth-scopes",
        "websub::websub-subscription", "websub::websub-events",
        "webmention::webmention-events", "webmention::webmention-read", "webmention::webmention-policy",
        "config::config-security", "util",
    ],
)
class ModuleMetadata
