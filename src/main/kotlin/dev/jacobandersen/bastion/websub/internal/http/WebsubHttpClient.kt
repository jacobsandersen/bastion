package dev.jacobandersen.bastion.websub.internal.http

import dev.jacobandersen.bastion.util.HttpUtil.isTransientStatus
import dev.jacobandersen.bastion.util.StringUtil.excerpt
import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

sealed interface PublishResult {
    data class Success(
        val statusCode: Int,
    ) : PublishResult

    data class Failure(
        val statusCode: Int?,
        val message: String,
        val retryable: Boolean,
    ) : PublishResult
}

@Service
class WebsubHttpClient(
    private val config: WebsubConfig,
) {
    private val client: RestClient =
        RestClient
            .builder()
            .requestFactory(requestFactory(config))
            .requestInterceptor(WebsubHttpLoggingInterceptor())
            .build()

    private fun requestFactory(config: WebsubConfig): ClientHttpRequestFactory {
        val httpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
                .build()

        return JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(config.readTimeoutSeconds))
        }
    }

    internal fun publish(
        hubUrl: String,
        topicUrl: String,
    ): PublishResult {
        val payload = LinkedMultiValueMap<String, String>()
        payload.add("hub.mode", "publish")
        payload.add("hub.url", topicUrl)
        return postForm(hubUrl, payload)
    }

    /** Sends a subscribe or unsubscribe request; a 2xx only means the hub will verify intent. */
    internal fun subscription(
        hubUrl: String,
        mode: String,
        topicUrl: String,
        callbackUrl: String,
        secret: String?,
        leaseSeconds: Long?,
    ): PublishResult {
        val payload = LinkedMultiValueMap<String, String>()
        payload.add("hub.mode", mode)
        payload.add("hub.topic", topicUrl)
        payload.add("hub.callback", callbackUrl)
        secret?.let { payload.add("hub.secret", it) }
        leaseSeconds?.let { payload.add("hub.lease_seconds", it.toString()) }
        return postForm(hubUrl, payload)
    }

    private fun postForm(
        hubUrl: String,
        payload: LinkedMultiValueMap<String, String>,
    ): PublishResult =
        try {
            val statusCode =
                client
                    .post()
                    .uri(hubUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .statusCode
                    .value()

            if (statusCode in 200..299) {
                PublishResult.Success(statusCode)
            } else {
                PublishResult.Failure(statusCode, "HTTP $statusCode", isTransientStatus(statusCode))
            }
        } catch (e: RestClientResponseException) {
            val statusCode = e.statusCode.value()
            val message = describeHttpError(statusCode, e.responseBodyAsString)
            PublishResult.Failure(statusCode, message, isTransientStatus(statusCode))
        } catch (e: RestClientException) {
            PublishResult.Failure(null, e.message ?: e::class.simpleName ?: "Request failed", true)
        }

    private fun describeHttpError(
        statusCode: Int,
        responseBody: String?,
    ): String {
        val body = responseBody?.takeIf { it.isNotBlank() }?.excerpt(MAX_ERROR_BODY_LENGTH)
        return if (body != null) "HTTP $statusCode: $body" else "HTTP $statusCode"
    }

    companion object {
        const val MAX_ERROR_BODY_LENGTH = 2000
    }
}
