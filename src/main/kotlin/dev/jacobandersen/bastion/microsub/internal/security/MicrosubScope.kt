package dev.jacobandersen.bastion.microsub.internal.security

import dev.jacobandersen.bastion.indieauth.scopes.IndieAuthScopeContributor
import org.springframework.stereotype.Component

/**
 * The Microsub scope vocabulary. These are the spec's bare names, not
 * module-prefixed ones: reader clients request `read follow mute block
 * channels` and would get no working token otherwise.
 */
enum class MicrosubScope {
    READ,
    FOLLOW,
    MUTE,
    BLOCK,
    CHANNELS,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(scope: String): MicrosubScope? = entries.find { it.wireName == scope }
    }
}

@Component
class MicrosubScopeContributor : IndieAuthScopeContributor {
    override fun scopes(): Set<String> = MicrosubScope.entries.map { it.wireName }.toSet()
}
