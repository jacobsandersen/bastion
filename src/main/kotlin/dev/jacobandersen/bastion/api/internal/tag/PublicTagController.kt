package dev.jacobandersen.bastion.api.internal.tag

import dev.jacobandersen.bastion.api.internal.dto.Pagination
import dev.jacobandersen.bastion.api.internal.tag.dto.TagListResponse
import dev.jacobandersen.bastion.api.internal.tag.dto.TagResponse
import dev.jacobandersen.bastion.post.read.PostReadService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tags")
class PublicTagController(
    private val postReadService: PostReadService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) offset: Int?,
    ): TagListResponse {
        val page = postReadService.tags(limit, offset)
        val tags = page.tags.map { TagResponse(tag = it.tag, count = it.count) }
        return TagListResponse(
            tags = tags,
            pagination =
                Pagination(
                    limit = page.limit,
                    offset = page.offset,
                    count = tags.size,
                    total = page.total,
                    hasMore = (page.offset + tags.size) < page.total,
                ),
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "bad request")))
}
