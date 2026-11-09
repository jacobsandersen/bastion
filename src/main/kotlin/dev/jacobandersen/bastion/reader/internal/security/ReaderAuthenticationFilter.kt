package dev.jacobandersen.bastion.reader.internal.security

import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.reader.internal.ReaderApi
import dev.jacobandersen.bastion.reader.internal.web.ReaderError
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Authenticates reader API requests with a Bastion-issued access token from the `Authorization: Bearer` header
 * only (no form or query token). Missing token: 401; unknown or expired token: 403. The capability document
 * (`GET /reader/v1`) and CORS preflights are public. The controller checks the scope each route needs.
 */
class ReaderAuthenticationFilter(
    private val accessTokenQueryService: AccessTokenQueryService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "OPTIONS" ||
            (request.method in PUBLIC_METHODS && request.requestURI.trimEnd('/') == ReaderApi.PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)?.trim()?.takeIf { it.isNotEmpty() }
        val parts = header?.split(Regex("\\s+"), limit = 2)
        if (parts == null || parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
            return write(response, ReaderError.unauthorized())
        }

        val issued = accessTokenQueryService.resolve(parts[1]) ?: return write(response, ReaderError.forbidden())
        SecurityContextHolder.getContext().authentication =
            ReaderAuthentication(me = issued.me, clientId = issued.clientId, scopes = issued.scope.toSet())
        filterChain.doFilter(request, response)
    }

    private fun write(
        response: HttpServletResponse,
        error: ReaderError,
    ) {
        response.status = error.status.value()
        if (error.status.value() == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        }
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, error.body())
    }

    private companion object {
        val PUBLIC_METHODS = setOf("GET", "HEAD")
    }
}
