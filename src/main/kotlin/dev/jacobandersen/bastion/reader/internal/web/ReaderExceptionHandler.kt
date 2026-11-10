package dev.jacobandersen.bastion.reader.internal.web

import dev.jacobandersen.bastion.reader.internal.ReaderApi
import dev.jacobandersen.bastion.reader.internal.security.ReaderAuthentication
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val logger = KotlinLogging.logger {}

/**
 * The scope check every reader route but the capability document makes: the Bearer token's scopes must include
 * [scope] (the Microsub `read` scope unless said otherwise). Returns the authentication, so a caller can read its client.
 */
internal fun requireScope(scope: String = ReaderApi.REQUIRED_SCOPE): ReaderAuthentication {
    val auth = SecurityContextHolder.getContext().authentication as? ReaderAuthentication ?: throw ReaderError.unauthorized()
    if (scope !in auth.scopes) throw ReaderError.insufficientScope(scope)
    return auth
}

/** Answers errors of every reader controller in the `error`/`error_description` shape. */
@RestControllerAdvice(basePackageClasses = [ReaderExceptionHandler::class])
class ReaderExceptionHandler {
    // An explicit JSON content type keeps these answers valid for the event stream route, whose Accept is text/event-stream.
    @ExceptionHandler(ReaderError::class)
    fun handle(error: ReaderError): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_JSON).body(error.body())

    /** Anything else keeps the `error`/`error_description` shape: framework 4xx errors map to `invalid_request`. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ResponseEntity<Map<String, Any>> {
        if (error is ErrorResponse && error.statusCode.is4xxClientError) {
            val body = ReaderError(HttpStatus.valueOf(error.statusCode.value()), "invalid_request", error.body.detail ?: "Invalid request")
            return ResponseEntity
                .status(body.status)
                .headers(error.headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.body())
        }
        logger.error(error) { "Unexpected error handling a reader API request" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("error" to "server_error", "error_description" to "The request could not be processed"))
    }
}
