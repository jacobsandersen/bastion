package dev.jacobandersen.bastion.api.internal.dto

data class Pagination(
    val limit: Int,
    val offset: Int,
    val count: Int,
    val total: Long,
    val hasMore: Boolean,
)
