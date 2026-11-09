package dev.jacobandersen.bastion.indieauth.metadata

/**
 * Contributes extra members to the IndieAuth authorization server metadata document (RFC 8414 section 2 allows
 * additional parameters). This is how a module advertises itself without the provider knowing about it.
 *
 * Member names must be vendor-namespaced (for example `bastion_reader_api`) so they never collide with a registered
 * metadata name. [baseUrl] is Bastion's public URL without a trailing slash.
 */
fun interface IndieAuthMetadataContributor {
    fun metadata(baseUrl: String): Map<String, Any>
}
