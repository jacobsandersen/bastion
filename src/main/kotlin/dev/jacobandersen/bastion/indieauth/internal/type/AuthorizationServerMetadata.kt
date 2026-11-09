package dev.jacobandersen.bastion.indieauth.internal.type

import com.fasterxml.jackson.annotation.JsonAnyGetter
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

/**
 * OAuth 2.0 / IndieAuth authorization server metadata (RFC 8414, IndieAuth
 * 4.1.1), served from `/.well-known/oauth-authorization-server` so clients can
 * discover Bastion as an IndieAuth provider without any hard-coded endpoints.
 *
 * Discovery of the metadata URL itself happens via the `indieauth-metadata`
 * link relation on the user's profile URL; the `.well-known` path is served
 * for compatibility with generic OAuth 2.0 clients. Optional endpoints
 * (introspection, revocation, userinfo) are omitted until implemented.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AuthorizationServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val introspectionEndpoint: String? = null,
    val revocationEndpoint: String? = null,
    val revocationEndpointAuthMethodsSupported: List<String>? = null,
    val scopesSupported: List<String> = emptyList(),
    val responseTypesSupported: List<String> = listOf("code"),
    val grantTypesSupported: List<String> = listOf("authorization_code"),
    val codeChallengeMethodsSupported: List<String> = listOf("S256"),
    val authorizationResponseIssParameterSupported: Boolean = true,
    val userinfoEndpoint: String? = null,
    /** Vendor-namespaced members contributed by other modules, serialized at the top level. */
    @get:JsonAnyGetter val extensions: Map<String, Any> = emptyMap(),
)
