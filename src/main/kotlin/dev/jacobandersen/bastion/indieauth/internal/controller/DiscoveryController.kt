package dev.jacobandersen.bastion.indieauth.internal.controller

import dev.jacobandersen.bastion.indieauth.internal.IndieAuthEndpoints
import dev.jacobandersen.bastion.indieauth.internal.scope.ScopeCatalog
import dev.jacobandersen.bastion.indieauth.internal.type.AuthorizationServerMetadata
import dev.jacobandersen.bastion.indieauth.internal.util.Issuers
import dev.jacobandersen.bastion.indieauth.metadata.IndieAuthMetadataContributor
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the discovery endpoints clients use to learn Bastion's IndieAuth
 * server: RFC 8414 / IndieAuth 4.1.1 metadata plus the legacy `.well-known`
 * endpoint URL probes.
 *
 * Clients discover the metadata URL itself via the `indieauth-metadata` link
 * relation published on the user's profile URL (`bastion.indieauth.me`,
 * an external site with a manual snippet); the `.well-known` path here is
 * served for compatibility with generic OAuth 2.0 clients.
 */
@RestController
class DiscoveryController(
    @Value($$"${bastion.public-url}") private val publicUrl: String,
    private val scopeCatalog: ScopeCatalog,
    private val metadataContributors: List<IndieAuthMetadataContributor>,
) {
    @PostConstruct
    fun validateIssuer() {
        Issuers.issuer(publicUrl)
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    fun authorizationServer(): AuthorizationServerMetadata =
        AuthorizationServerMetadata(
            issuer = issuer(),
            authorizationEndpoint = authorizationEndpoint(),
            tokenEndpoint = tokenEndpoint(),
            introspectionEndpoint = baseUrl() + IndieAuthEndpoints.INTROSPECTION,
            revocationEndpoint = baseUrl() + IndieAuthEndpoints.REVOCATION,
            revocationEndpointAuthMethodsSupported = listOf("none"),
            scopesSupported = scopeCatalog.allowedScopes.sorted(),
            grantTypesSupported = listOf("authorization_code", "refresh_token"),
            userinfoEndpoint = baseUrl() + IndieAuthEndpoints.USERINFO,
            extensions = extensions(),
        )

    @GetMapping(
        path = ["/.well-known/oauth-authorization-endpoint"],
        produces = [MediaType.TEXT_PLAIN_VALUE],
    )
    fun authorizationEndpoint(): String = baseUrl() + IndieAuthEndpoints.AUTHORIZATION

    @GetMapping(
        path = ["/.well-known/oauth-token-endpoint"],
        produces = [MediaType.TEXT_PLAIN_VALUE],
    )
    fun tokenEndpoint(): String = baseUrl() + IndieAuthEndpoints.TOKEN

    /** Vendor-namespaced members other modules add to the metadata. */
    private fun extensions(): Map<String, Any> = metadataContributors.fold(emptyMap()) { all, c -> all + c.metadata(baseUrl()) }

    private fun issuer(): String = Issuers.issuer(publicUrl)

    private fun baseUrl(): String = Issuers.baseUrl(publicUrl)
}
