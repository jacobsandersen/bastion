package dev.jacobandersen.bastion.reader.internal.web

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

/** The public capability document at `GET /reader/v1`. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ReaderCapabilities(
    val api: String,
    val version: Int,
    val microsubEndpoint: String,
    val features: List<String>,
)
