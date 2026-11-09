package dev.jacobandersen.bastion.reader.internal

import dev.jacobandersen.bastion.indieauth.metadata.IndieAuthMetadataContributor
import org.springframework.stereotype.Component

/** Advertises the reader API in the IndieAuth server metadata so a client can detect it without probing. */
@Component
class ReaderMetadataContributor : IndieAuthMetadataContributor {
    override fun metadata(baseUrl: String): Map<String, Any> = mapOf(ReaderApi.METADATA_MEMBER to baseUrl + ReaderApi.PATH)
}
