package dev.jacobandersen.bastion.reader.internal.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

class ReaderAuthentication(
    val me: String,
    val clientId: String,
    val scopes: Set<String>,
) : AbstractAuthenticationToken(scopes.map { SimpleGrantedAuthority(it) }) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = me
}
