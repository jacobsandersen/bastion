package dev.jacobandersen.bastion.microsub.internal.security

import dev.jacobandersen.bastion.config.security.applyBastionDefaults
import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfigurationSource
import tools.jackson.databind.ObjectMapper

@Configuration
class MicrosubSecurityConfig(
    accessTokenQueryService: AccessTokenQueryService,
    objectMapper: ObjectMapper,
    @Qualifier("publicCorsConfigurationSource") private val publicCorsConfigurationSource: CorsConfigurationSource,
) {
    private val authFilter = MicrosubAuthenticationFilter(accessTokenQueryService, objectMapper)

    @Bean
    @Order(2)
    fun microsubSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher("/microsub", "/microsub/**")
            .applyBastionDefaults()
            .cors { it.configurationSource(publicCorsConfigurationSource) }
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
