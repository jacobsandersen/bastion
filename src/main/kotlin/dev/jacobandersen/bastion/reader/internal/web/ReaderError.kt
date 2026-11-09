package dev.jacobandersen.bastion.reader.internal.web

import org.springframework.http.HttpStatus

/** A reader API error in the same `error`/`error_description` shape Microsub and Micropub use. */
class ReaderError(
    val status: HttpStatus,
    val error: String,
    val description: String,
) : RuntimeException(description) {
    fun body(): Map<String, String> = mapOf("error" to error, "error_description" to description)

    companion object {
        fun unauthorized() = ReaderError(HttpStatus.UNAUTHORIZED, "unauthorized", "An access token is required")

        fun forbidden() = ReaderError(HttpStatus.FORBIDDEN, "forbidden", "The access token is not valid")

        fun insufficientScope(scope: String) =
            ReaderError(HttpStatus.FORBIDDEN, "insufficient_scope", "This request requires the '$scope' scope")

        fun channelNotFound(uid: String) = ReaderError(HttpStatus.NOT_FOUND, "not_found", "Channel '$uid' does not exist")
    }
}
