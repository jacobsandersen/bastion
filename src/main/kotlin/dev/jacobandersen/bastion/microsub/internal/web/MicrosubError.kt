package dev.jacobandersen.bastion.microsub.internal.web

import org.springframework.http.HttpStatus

/** A Microsub error, in the Micropub error format the spec adopts. */
class MicrosubError(
    val status: HttpStatus,
    val error: String,
    val description: String,
) : RuntimeException(description) {
    fun body(): Map<String, String> = mapOf("error" to error, "error_description" to description)

    companion object {
        fun invalidRequest(description: String) = MicrosubError(HttpStatus.BAD_REQUEST, "invalid_request", description)

        fun unauthorized() = MicrosubError(HttpStatus.UNAUTHORIZED, "unauthorized", "An access token is required")

        fun forbidden() = MicrosubError(HttpStatus.FORBIDDEN, "forbidden", "The access token is not valid")

        fun insufficientScope(scope: String) =
            MicrosubError(HttpStatus.FORBIDDEN, "insufficient_scope", "This action requires the '$scope' scope")
    }
}
