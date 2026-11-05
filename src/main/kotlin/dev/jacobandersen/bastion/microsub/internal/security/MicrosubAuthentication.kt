package dev.jacobandersen.bastion.microsub.internal.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

class MicrosubAuthentication(
    val me: String,
    val clientId: String,
    val scopes: Set<MicrosubScope>,
) : AbstractAuthenticationToken(scopes.map { SimpleGrantedAuthority(it.name) }) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = me
}
