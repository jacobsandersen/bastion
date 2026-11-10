package dev.jacobandersen.bastion.reader.internal.web

import org.springframework.http.HttpStatus

/** A reader API error in the same `error`/`error_description` shape Microsub and Micropub use. */
class ReaderError(
    val status: HttpStatus,
    val error: String,
    val description: String,
    /** Further members of the error body, e.g. the counts of a refused bulk delete. */
    val details: Map<String, Any> = emptyMap(),
) : RuntimeException(description) {
    fun body(): Map<String, Any> = mapOf("error" to error, "error_description" to description) + details

    companion object {
        fun unauthorized() = ReaderError(HttpStatus.UNAUTHORIZED, "unauthorized", "An access token is required")

        fun forbidden() = ReaderError(HttpStatus.FORBIDDEN, "forbidden", "The access token is not valid")

        fun insufficientScope(scope: String) =
            ReaderError(HttpStatus.FORBIDDEN, "insufficient_scope", "This request requires the '$scope' scope")

        fun invalidRequest(description: String) = ReaderError(HttpStatus.BAD_REQUEST, "invalid_request", description)

        fun scopeChanged(
            matched: Int,
            expected: Int,
        ) = ReaderError(
            HttpStatus.CONFLICT,
            "scope_changed",
            "$matched entries match now but only $expected were expected; run the dry run again",
            mapOf("matched" to matched, "expected" to expected),
        )

        fun busy() = ReaderError(HttpStatus.CONFLICT, "busy", "Another bulk delete is running; try again shortly")

        fun channelNotFound(uid: String) = ReaderError(HttpStatus.NOT_FOUND, "not_found", "Channel '$uid' does not exist")
    }
}
