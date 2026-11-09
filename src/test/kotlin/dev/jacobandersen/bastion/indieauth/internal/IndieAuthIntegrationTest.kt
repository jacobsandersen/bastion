package dev.jacobandersen.bastion.indieauth.internal

import dev.jacobandersen.bastion.TestcontainersConfiguration
import dev.jacobandersen.bastion.indieauth.internal.data.entity.AccessTokenEntity
import dev.jacobandersen.bastion.indieauth.internal.data.entity.AuthRequestEntity
import dev.jacobandersen.bastion.indieauth.internal.data.entity.AuthorizationCodeEntity
import dev.jacobandersen.bastion.indieauth.internal.data.repository.AccessTokenRepository
import dev.jacobandersen.bastion.indieauth.internal.data.repository.AuthRequestRepository
import dev.jacobandersen.bastion.indieauth.internal.data.repository.AuthorizationCodeRepository
import dev.jacobandersen.bastion.indieauth.internal.identity.GitHubIdentityProvider
import dev.jacobandersen.bastion.indieauth.internal.identity.ProviderIdentity
import dev.jacobandersen.bastion.indieauth.internal.security.Pkce
import dev.jacobandersen.bastion.indieauth.internal.security.Tokens
import dev.jacobandersen.bastion.indieauth.internal.service.IndieAuthRowPurgeService
import dev.jacobandersen.bastion.indieauth.internal.service.OwnerVerification
import dev.jacobandersen.bastion.indieauth.internal.service.OwnerVerifier
import dev.jacobandersen.bastion.micropub.internal.security.MicropubToken
import dev.jacobandersen.bastion.micropub.internal.security.MicropubTokenScope
import dev.jacobandersen.bastion.micropub.internal.security.MicropubTokenValidator
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.dashboard.enabled=false",
        "jobrunr.background-job-server.enabled=false",
    ],
)
class IndieAuthIntegrationTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var tokenValidator: MicropubTokenValidator

    @Autowired
    lateinit var authRequestRepository: AuthRequestRepository

    @Autowired
    lateinit var authorizationCodeRepository: AuthorizationCodeRepository

    @Autowired
    lateinit var accessTokenRepository: AccessTokenRepository

    @Autowired
    lateinit var refreshTokenRepository: dev.jacobandersen.bastion.indieauth.internal.data.repository.RefreshTokenRepository

    @Autowired
    lateinit var rowPurgeService: IndieAuthRowPurgeService

    @MockitoBean
    lateinit var githubIdentityProvider: GitHubIdentityProvider

    @MockitoBean
    lateinit var ownerVerifier: OwnerVerifier

    lateinit var mockMvc: MockMvc

    private val clientId = "https://client.example"
    private val redirectUri = "https://client.example/callback"

    @BeforeEach
    fun setUp() {
        `when`(githubIdentityProvider.resolveIdentity(anyString()))
            .thenReturn(ProviderIdentity("github", "12345", "https://github.com/someone"))
        `when`(ownerVerifier.verify(anyString())).thenReturn(OwnerVerification.Verified)
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    // -------------------------------------------------------------- discovery

    @Test
    fun `authorization server metadata is served`() {
        mockMvc
            .perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issuer").value("https://bastion.test/"))
            .andExpect(jsonPath("$.authorization_endpoint").value("https://bastion.test/indieauth/auth"))
            .andExpect(jsonPath("$.token_endpoint").value("https://bastion.test/indieauth/token"))
            .andExpect(jsonPath("$.introspection_endpoint").value("https://bastion.test/indieauth/introspect"))
            .andExpect(jsonPath("$.revocation_endpoint").value("https://bastion.test/indieauth/revocation"))
            .andExpect(jsonPath("$.revocation_endpoint_auth_methods_supported[0]").value("none"))
            .andExpect(jsonPath("$.userinfo_endpoint").value("https://bastion.test/indieauth/userinfo"))
            .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
            .andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true))
            .andExpect(jsonPath("$.grant_types_supported").isArray())
            .andExpect(jsonPath("$.bastion_reader_api").value("https://bastion.test/reader/v1"))
            .andExpect(
                jsonPath(
                    "$.scopes_supported",
                    hasItems("profile", "email", "create", "update", "delete", "undelete", "media"),
                ),
            )
    }

    @Test
    fun `legacy token endpoint discovery is served`() {
        mockMvc
            .perform(get("/.well-known/oauth-token-endpoint"))
            .andExpect(status().isOk)
    }

    // ------------------------------------------------------------- full flow

    @Test
    fun `full authorization code flow issues a token resolvable by micropub`() {
        val verifier = "test-verifier-value"
        val challenge = Pkce.s256(verifier)

        val state = beginAuthorization(challenge)
        val code = completeAuthorization(state)

        val body =
            mockMvc
                .perform(
                    post("/indieauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", verifier),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.me").value("https://bastion.test"))
                .andExpect(jsonPath("$.scope").value("create"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn()
                .response
                .contentAsString

        val accessToken = mapper.readTree(body).path("access_token").asText()
        assertTrue(accessToken.isNotBlank())

        // The issued token must be directly resolvable by Micropub validation.
        val auth = tokenValidator.validateToken(accessToken)
        assertEquals("https://bastion.test", auth.getName())
        val token = auth.getDetails() as MicropubToken
        assertEquals(listOf(MicropubTokenScope.CREATE), token.scope)
    }

    @Test
    fun `login-only flow without scope redeems a profile url at the authorization endpoint`() {
        val verifier = "test-verifier-value"
        val challenge = Pkce.s256(verifier)

        val state = beginAuthorization(challenge, scope = null)
        val code = completeAuthorization(state)

        mockMvc
            .perform(
                post("/indieauth/auth")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code")
                    .param("code", code)
                    .param("client_id", clientId)
                    .param("redirect_uri", redirectUri)
                    .param("code_verifier", verifier),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.me").value("https://bastion.test"))
            .andExpect(jsonPath("$.access_token").doesNotExist())
    }

    @Test
    fun `token endpoint rejects an empty-scope code`() {
        val verifier = "test-verifier-value"
        val challenge = Pkce.s256(verifier)

        val state = beginAuthorization(challenge, scope = null)
        val code = completeAuthorization(state)

        mockMvc
            .perform(
                post("/indieauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code")
                    .param("code", code)
                    .param("client_id", clientId)
                    .param("redirect_uri", redirectUri)
                    .param("code_verifier", verifier),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_grant"))
    }

    // ----------------------------------------------------------------- CSRF

    @Test
    fun `callback with an unknown state is rejected`() {
        mockMvc
            .perform(get("/indieauth/auth/callback").param("state", "bogus").param("code", "github-code"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun `authorization without a code challenge is allowed for backwards compatibility`() {
        mockMvc
            .perform(
                get("/indieauth/auth")
                    .param("client_id", clientId)
                    .param("redirect_uri", redirectUri)
                    .param("state", "client-state")
                    .param("scope", "create")
                    .param("response_type", "code"),
            ).andExpect(status().isFound)
            .andExpect(
                header().string(
                    HttpHeaders.LOCATION,
                    org.hamcrest.Matchers.startsWith("https://herald.test/auth?"),
                ),
            )
    }

    @Test
    fun `replayed state is rejected`() {
        val state = beginAuthorization()

        mockMvc
            .perform(get("/indieauth/auth/callback").param("state", state).param("code", "github-code"))
            .andExpect(status().isFound)

        mockMvc
            .perform(get("/indieauth/auth/callback").param("state", state).param("code", "github-code"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun `token exchange with a wrong pkce verifier is rejected`() {
        val verifier = "test-verifier-value"
        val state = beginAuthorization(Pkce.s256(verifier))
        val code = completeAuthorization(state)

        mockMvc
            .perform(
                post("/indieauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code")
                    .param("code", code)
                    .param("client_id", clientId)
                    .param("redirect_uri", redirectUri)
                    .param("code_verifier", "wrong-verifier"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_grant"))
    }

    @Test
    fun `token exchange with an unknown code is rejected`() {
        mockMvc
            .perform(
                post("/indieauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code")
                    .param("code", "bogus-code")
                    .param("client_id", clientId)
                    .param("redirect_uri", redirectUri),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_grant"))
    }

    @Test
    fun `token exchange with an unsupported grant type is rejected`() {
        mockMvc
            .perform(
                post("/indieauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "client_credentials"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("unsupported_grant_type"))
    }

    @Test
    fun `refresh flow rotates tokens and narrows scope`() {
        val verifier = "test-verifier-value"
        val state = beginAuthorization(Pkce.s256(verifier), scope = "create update")
        val code = completeAuthorization(state)

        val body =
            mockMvc
                .perform(
                    post("/indieauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", verifier),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        val refreshToken = mapper.readTree(body).path("refresh_token").asText()
        assertTrue(refreshToken.isNotBlank())

        val rotatedBody =
            mockMvc
                .perform(
                    post("/indieauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId)
                        .param("scope", "create"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.scope").value("create"))
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andReturn()
                .response
                .contentAsString
        val replacement = mapper.readTree(rotatedBody).path("refresh_token").asText()
        assertTrue(replacement.isNotBlank() && replacement != refreshToken)

        mockMvc
            .perform(
                post("/indieauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "refresh_token")
                    .param("refresh_token", refreshToken)
                    .param("client_id", clientId),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_grant"))
    }

    @Test
    fun `refresh cannot widen scope`() {
        val verifier = "test-verifier-value"
        val state = beginAuthorization(Pkce.s256(verifier), scope = "create")
        val code = completeAuthorization(state)

        val body =
            mockMvc
                .perform(
                    post("/indieauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", verifier),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        val refreshToken = mapper.readTree(body).path("refresh_token").asText()

        mockMvc
            .perform(
                post("/indieauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "refresh_token")
                    .param("refresh_token", refreshToken)
                    .param("client_id", clientId)
                    .param("scope", "create delete"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_scope"))
    }

    @Test
    fun `introspection reports active tokens and hides inactive ones`() {
        val tokens = issueScopedTokens("create")

        mockMvc
            .perform(
                post("/indieauth/introspect")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.first}")
                    .param("token", tokens.first),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.me").value("https://bastion.test"))
            .andExpect(jsonPath("$.client_id").value(clientId))
            .andExpect(jsonPath("$.scope").value("create"))

        mockMvc
            .perform(
                post("/indieauth/introspect")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.first}")
                    .param("token", "bogus-token"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.me").doesNotExist())
    }

    @Test
    fun `introspection requires bearer authorization`() {
        mockMvc
            .perform(
                post("/indieauth/introspect")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("token", "whatever"),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `revocation disables a token`() {
        val tokens = issueScopedTokens("create")

        mockMvc
            .perform(
                post("/indieauth/revocation")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("token", tokens.first),
            ).andExpect(status().isOk)

        mockMvc
            .perform(
                post("/indieauth/introspect")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.second}")
                    .param("token", tokens.first),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
    }

    @Test
    fun `revocation of an unknown token still succeeds`() {
        mockMvc
            .perform(
                post("/indieauth/revocation")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("token", "unknown-token"),
            ).andExpect(status().isOk)
    }

    @Test
    fun `userinfo requires the profile scope`() {
        val tokens = issueScopedTokens("create")

        mockMvc
            .perform(get("/indieauth/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.first}"))
            .andExpect(status().isForbidden)

        mockMvc
            .perform(get("/indieauth/userinfo"))
            .andExpect(status().isUnauthorized)
    }

    private fun issueScopedTokens(scope: String): Pair<String, String> {
        val verifier = "test-verifier-value-$scope-${System.nanoTime()}"
        val state = beginAuthorization(Pkce.s256(verifier), scope = scope)
        val code = completeAuthorization(state)
        val body =
            mockMvc
                .perform(
                    post("/indieauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", verifier),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        val first = mapper.readTree(body).path("access_token").asText()
        val secondState = beginAuthorization(Pkce.s256("$verifier-2"), scope = scope)
        val secondCode = completeAuthorization(secondState)
        val secondBody =
            mockMvc
                .perform(
                    post("/indieauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", secondCode)
                        .param("client_id", clientId)
                        .param("redirect_uri", redirectUri)
                        .param("code_verifier", "$verifier-2"),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString
        return first to mapper.readTree(secondBody).path("access_token").asText()
    }

    @Test
    fun `authorization with an invalid redirect uri is rejected`() {
        mockMvc
            .perform(
                get("/indieauth/auth")
                    .param("client_id", clientId)
                    .param("redirect_uri", "not-a-url")
                    .param("state", "client-state"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_request"))
    }

    @Test
    fun `authorization with a disallowed scope redirects an error to the client`() {
        mockMvc
            .perform(
                get("/indieauth/auth")
                    .param("client_id", clientId)
                    .param("redirect_uri", redirectUri)
                    .param("state", "client-state")
                    .param("scope", "create admin"),
            ).andExpect(status().isFound)
            .andExpect(
                header().string(
                    HttpHeaders.LOCATION,
                    org.hamcrest.Matchers.containsString("error=invalid_scope"),
                ),
            )
    }

    // -------------------------------------------------------------- row purge

    @Test
    fun `purge removes dead rows and keeps live ones`() {
        val now = Instant.now()
        val challenge = Pkce.s256("purge-verifier")

        authRequestRepository.save(
            AuthRequestEntity(
                stateHash = Tokens.sha256("expired-state"),
                clientId = clientId,
                redirectUri = redirectUri,
                me = "https://bastion.test",
                scope = "create",
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                expiresAt = now.minusSeconds(3600),
                createdAt = now.minusSeconds(7200),
            ),
        )
        authRequestRepository.save(
            AuthRequestEntity(
                stateHash = Tokens.sha256("live-state"),
                clientId = clientId,
                redirectUri = redirectUri,
                me = "https://bastion.test",
                scope = "create",
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                expiresAt = now.plusSeconds(3600),
                createdAt = now,
            ),
        )

        authorizationCodeRepository.save(
            AuthorizationCodeEntity(
                codeHash = Tokens.sha256("used-old-code"),
                clientId = clientId,
                redirectUri = redirectUri,
                me = "https://bastion.test",
                scope = "create",
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                expiresAt = now.minusSeconds(60),
                usedAt = now.minusSeconds(86400 * 2),
                createdAt = now.minusSeconds(86400 * 2).minusSeconds(60),
            ),
        )
        authorizationCodeRepository.save(
            AuthorizationCodeEntity(
                codeHash = Tokens.sha256("expired-unused-code"),
                clientId = clientId,
                redirectUri = redirectUri,
                me = "https://bastion.test",
                scope = "create",
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                expiresAt = now.minusSeconds(86400 * 2),
                createdAt = now.minusSeconds(86400 * 2).minusSeconds(60),
            ),
        )
        authorizationCodeRepository.save(
            AuthorizationCodeEntity(
                codeHash = Tokens.sha256("recently-used-code"),
                clientId = clientId,
                redirectUri = redirectUri,
                me = "https://bastion.test",
                scope = "create",
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                expiresAt = now.minusSeconds(30),
                usedAt = now.minusSeconds(60),
                createdAt = now.minusSeconds(600),
            ),
        )

        accessTokenRepository.save(
            AccessTokenEntity(
                tokenHash = Tokens.sha256("expired-token"),
                me = "https://bastion.test",
                clientId = clientId,
                scope = "create",
                issuedAt = now.minusSeconds(86400 * 31),
                expiresAt = now.minusSeconds(60),
            ),
        )
        accessTokenRepository.save(
            AccessTokenEntity(
                tokenHash = Tokens.sha256("live-token"),
                me = "https://bastion.test",
                clientId = clientId,
                scope = "create",
                issuedAt = now,
                expiresAt = now.plusSeconds(86400 * 30),
            ),
        )
        refreshTokenRepository.save(
            dev.jacobandersen.bastion.indieauth.internal.data.entity.RefreshTokenEntity(
                tokenHash = Tokens.sha256("expired-refresh"),
                me = "https://bastion.test",
                clientId = clientId,
                scope = "create",
                issuedAt = now.minusSeconds(86400 * 100),
                expiresAt = now.minusSeconds(86400 * 2),
            ),
        )
        refreshTokenRepository.save(
            dev.jacobandersen.bastion.indieauth.internal.data.entity.RefreshTokenEntity(
                tokenHash = Tokens.sha256("live-refresh"),
                me = "https://bastion.test",
                clientId = clientId,
                scope = "create",
                issuedAt = now,
                expiresAt = now.plusSeconds(86400 * 90),
            ),
        )

        rowPurgeService.purge(now)

        assertNull(authRequestRepository.findByStateHash(Tokens.sha256("expired-state")))
        assertNull(authorizationCodeRepository.findByCodeHash(Tokens.sha256("used-old-code")))
        assertNull(authorizationCodeRepository.findByCodeHash(Tokens.sha256("expired-unused-code")))
        assertNull(accessTokenRepository.findByTokenHash(Tokens.sha256("expired-token")))
        assertNull(refreshTokenRepository.findByTokenHash(Tokens.sha256("expired-refresh")))

        assertNotNull(authRequestRepository.findByStateHash(Tokens.sha256("live-state")))
        assertNotNull(authorizationCodeRepository.findByCodeHash(Tokens.sha256("recently-used-code")))
        assertNotNull(accessTokenRepository.findByTokenHash(Tokens.sha256("live-token")))
        assertNotNull(refreshTokenRepository.findByTokenHash(Tokens.sha256("live-refresh")))
    }

    // --------------------------------------------------------------- helpers

    private fun beginAuthorization(
        codeChallenge: String = Pkce.s256("integration-verifier"),
        scope: String? = "create",
    ): String {
        val request =
            get("/indieauth/auth")
                .param("client_id", clientId)
                .param("redirect_uri", redirectUri)
                .param("state", "client-state")
                .param("response_type", "code")
                .param("code_challenge", codeChallenge)
                .param("code_challenge_method", "S256")
        if (scope != null) {
            request.param("scope", scope)
        }
        val response =
            mockMvc
                .perform(request)
                .andExpect(status().isFound)
                .andReturn()
                .response

        val location = response.getHeader(HttpHeaders.LOCATION)!!
        assertTrue(location.startsWith("https://herald.test/auth?"), "expected herald redirect, got $location")
        return queryParam(location, "state")!!
    }

    private fun completeAuthorization(state: String): String {
        val response =
            mockMvc
                .perform(get("/indieauth/auth/callback").param("state", state).param("code", "github-code"))
                .andExpect(status().isFound)
                .andReturn()
                .response

        val location = response.getHeader(HttpHeaders.LOCATION)!!
        assertTrue(location.startsWith("$redirectUri?"), "expected client redirect, got $location")
        assertEquals("client-state", queryParam(location, "state"))
        assertEquals("https://bastion.test/", queryParam(location, "iss"))
        return queryParam(location, "code")!!
    }

    private fun queryParam(
        url: String,
        name: String,
    ): String? =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .getFirst(name)
}
