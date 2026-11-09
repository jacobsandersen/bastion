package dev.jacobandersen.bastion.reader.internal.security

import dev.jacobandersen.bastion.config.security.applyBastionDefaults
import dev.jacobandersen.bastion.indieauth.query.AccessTokenQueryService
import dev.jacobandersen.bastion.reader.internal.ReaderApi
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
class ReaderSecurityConfig(
    accessTokenQueryService: AccessTokenQueryService,
    objectMapper: ObjectMapper,
    @Qualifier("publicCorsConfigurationSource") private val publicCorsConfigurationSource: CorsConfigurationSource,
) {
    private val authFilter = ReaderAuthenticationFilter(accessTokenQueryService, objectMapper)

    @Bean
    @Order(3)
    fun readerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher(ReaderApi.PATH, "${ReaderApi.PATH}/**")
            .applyBastionDefaults()
            .cors { it.configurationSource(publicCorsConfigurationSource) }
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
