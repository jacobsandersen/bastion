package dev.jacobandersen.bastion.microsub.internal.security

import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.microsub.internal.web.MicrosubError
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Authenticates Microsub requests with a Bastion-issued access token from the
 * `Authorization: Bearer` header or the `access_token` field of a form-encoded
 * POST body (not both). A token in the URL query string is never accepted, as
 * URLs end up in logs and referrers.
 * Missing token: 401; unknown or expired token: 403. Per-action scope checks
 * happen in the controller because the scope depends on `action`.
 */
class MicrosubAuthenticationFilter(
    private val accessTokenQueryService: AccessTokenQueryService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method == "OPTIONS") {
            filterChain.doFilter(request, response)
            return
        }

        val header = request.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.isNotBlank() }
        val param = bodyToken(request)
        val token =
            when {
                header != null && param != null -> {
                    return write(response, MicrosubError.invalidRequest("Only one form of access token may be provided"))
                }

                header != null -> {
                    val parts = header.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) {
                        return write(response, MicrosubError.unauthorized())
                    }
                    parts[1]
                }

                param != null -> {
                    param
                }

                else -> {
                    return write(response, MicrosubError.unauthorized())
                }
            }

        val issued = accessTokenQueryService.resolve(token) ?: return write(response, MicrosubError.forbidden())
        SecurityContextHolder.getContext().authentication =
            MicrosubAuthentication(
                me = issued.me,
                clientId = issued.clientId,
                scopes = issued.scope.mapNotNull(MicrosubScope::fromWire).toSet(),
            )
        filterChain.doFilter(request, response)
    }

    private fun bodyToken(request: HttpServletRequest): String? {
        val form = request.method == "POST" && request.contentType?.startsWith(FORM, ignoreCase = true) == true
        val inQuery = request.queryString?.split('&')?.any { it.substringBefore('=') == "access_token" } == true
        if (!form || inQuery) return null
        return request.getParameter("access_token")?.takeIf { it.isNotBlank() }
    }

    private fun write(
        response: HttpServletResponse,
        error: MicrosubError,
    ) {
        response.status = error.status.value()
        if (error.status.value() == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        }
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, error.body())
    }

    private companion object {
        const val FORM = "application/x-www-form-urlencoded"
    }
}
