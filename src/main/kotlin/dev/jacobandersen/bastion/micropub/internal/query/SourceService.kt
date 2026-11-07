package dev.jacobandersen.bastion.micropub.internal.query

import dev.jacobandersen.bastion.micropub.internal.type.resp.ApiResponse
import dev.jacobandersen.bastion.micropub.internal.type.resp.SourceListResponse
import dev.jacobandersen.bastion.post.query.PostQueryResult
import dev.jacobandersen.bastion.post.query.PostQueryService
import dev.jacobandersen.microformats2.Mf2Value
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class SourceService(
    private val postQueryService: PostQueryService,
) {
    fun getSource(params: MutableMap<String, Array<String>>): ApiResponse<*> {
        val url = params["url"]?.firstOrNull()
        params.remove("url")

        return if (url == null) {
            handleList(params)
        } else {
            handleOne(url, params)
        }
    }

    fun handleOne(
        url: String,
        params: Map<String, Array<String>>,
    ): ApiResponse<*> {
        val post =
            when (val result = postQueryService.byUrl(url)) {
                is PostQueryResult.Found -> {
                    result.post
                }

                is PostQueryResult.Gone -> {
                    return ApiResponse.Error.Gone()
                }

                is PostQueryResult.Invalid -> {
                    return ApiResponse.Error.InvalidRequest(errorDescription = result.message)
                }

                PostQueryResult.NotFound -> {
                    return ApiResponse.Error.InvalidRequest(errorDescription = "Post not found for URL: $url")
                }
            }

        logger.info { "Will look up post ${post.slug}" }

        val requestedProperties = params["properties"].takeUnless { it.isNullOrEmpty() }
        val filtered =
            if (requestedProperties == null) {
                post.post.setProperty("url", Mf2Value.String(post.url))
            } else {
                post.post.copy(properties = post.post.properties.filterKeys { it in requestedProperties })
            }

        // Per spec: when specific properties are requested the response is a
        // bare properties object; otherwise a full mf2 object.
        return if (requestedProperties == null) {
            ApiResponse.Success.Ok(filtered)
        } else {
            ApiResponse.Success.Ok(mapOf("properties" to filtered.properties))
        }
    }

    fun handleList(params: Map<String, Array<String>>): ApiResponse<*> {
        val limit = params["limit"]?.firstOrNull()
        val offset = params["offset"]?.firstOrNull()

        val parsedLimit = limit?.toIntOrNull()
        val parsedOffset = offset?.toIntOrNull()

        if (limit != null && parsedLimit == null) {
            return ApiResponse.Error.InvalidRequest(errorDescription = "limit must be an integer")
        }
        if (offset != null && parsedOffset == null) {
            return ApiResponse.Error.InvalidRequest(errorDescription = "offset must be an integer")
        }

        val requestedProperties = params["properties"].takeUnless { it.isNullOrEmpty() }?.toSet()

        val posts =
            try {
                postQueryService.list(parsedLimit, parsedOffset).map { view ->
                    // The canonical URL is always included so clients can send edits for listed posts.
                    val filtered =
                        if (requestedProperties == null) {
                            view.post
                        } else {
                            view.post.copy(properties = view.post.properties.filterKeys { it in requestedProperties })
                        }
                    filtered.setProperty("url", Mf2Value.String(view.url))
                }
            } catch (e: IllegalArgumentException) {
                return ApiResponse.Error.InvalidRequest(errorDescription = e.message ?: "Invalid source query")
            }

        return ApiResponse.Success.Ok(SourceListResponse(posts))
    }
}
