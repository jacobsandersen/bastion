package dev.jacobandersen.bastion.reader

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Bastion's reader API (`/reader/v1`): the extras Bastion's own feed reader client (Grimoire) uses on top of plain
 * Microsub - on-demand feed refresh, the live entries stream, feed details (format, WebSub state), the entry check that tells
 * a client which cached entries are gone and the entries the owner deleted. These live here
 * so the `microsub` module stays spec-aligned and works with any Microsub client.
 *
 * A client learns the API exists from the `bastion_reader_api` member of the IndieAuth server metadata and then reads
 * the capability document at `GET /reader/v1`. No public surface. The API only checks the Microsub `read` scope; it
 * defines no scope of its own.
 */
@PackageInfo
@ApplicationModule(
    type = ApplicationModule.Type.CLOSED,
    allowedDependencies = [
        "microsub::microsub-entries", "microsub::microsub-events", "microsub::microsub-feeds",
        "indieauth::indieauth-token-query", "indieauth::indieauth-metadata",
        "config::config-security",
    ],
)
class ModuleMetadata
