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

/** The body of `POST /reader/v1/channels/{uid}/entries/check`. */
data class EntryCheckRequest(
    val entry: List<String>? = null,
)
