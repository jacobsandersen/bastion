package dev.jacobandersen.bastion.post.internal.types

import dev.jacobandersen.bastion.post.internal.repository.PostRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Recomputes stored post types against the current discovery ruleset. The job
 * only updates the `type` column: it does not publish post events, touch
 * syndication state, or reconcile webmentions.
 */
@Service
class PostTypeReclassificationService(
    private val catalog: PostTypeCatalog,
    private val stateService: PostTypeStateService,
    private val postRepository: PostRepository,
    private val writer: PostTypeBackfillWriter,
) {
    fun initialize() {
        stateService.recordDesired(catalog.fingerprint)
    }

    fun reconcile() {
        val lease = Duration.ofMinutes(catalog.backfill.leaseMinutes.coerceAtLeast(1))
        val claimed = stateService.tryClaim(Instant.now(), lease) ?: return
        if (claimed != catalog.fingerprint) {
            stateService.release()
            logger.info {
                "Skipping post type reclassification: desired fingerprint $claimed " +
                    "does not match local ${catalog.fingerprint}"
            }
            return
        }

        val dryRun = catalog.backfill.dryRun
        val batchSize = catalog.backfill.batchSize.coerceAtLeast(1)
        var processed = 0L
        var changed = 0L

        try {
            var after: UUID? = null
            while (true) {
                val batch =
                    if (after == null) {
                        postRepository.findAllByOrderByIdAsc(PageRequest.of(0, batchSize))
                    } else {
                        postRepository.findByIdGreaterThanOrderByIdAsc(after, PageRequest.of(0, batchSize))
                    }
                if (batch.isEmpty()) break

                val changes =
                    batch.mapNotNull { entity ->
                        val id = requireNotNull(entity.id) { "Persisted post is missing its id" }
                        val newType = catalog.discover(entity.post)?.type
                        if (newType == entity.type) {
                            null
                        } else {
                            PostTypeChange(id, entity.type, newType)
                        }
                    }

                processed += batch.size
                changed += changes.size

                if (changes.isNotEmpty()) {
                    if (dryRun) {
                        changes.forEach { change ->
                            logger.info { "Post type dry-run: post ${change.id} would become '${change.type ?: "none"}'" }
                        }
                    } else {
                        writer.updateTypes(changes)
                    }
                }

                after = batch.last().id ?: break
                if (batch.size < batchSize) break
            }

            if (dryRun) {
                stateService.release()
                logger.info { "Post type dry-run complete: $processed processed, $changed would change" }
            } else {
                stateService.complete(claimed, processed, changed, Instant.now())
                logger.info { "Post type reclassification complete: $processed processed, $changed changed" }
            }
        } catch (e: Exception) {
            stateService.release()
            logger.error(e) { "Post type reclassification failed" }
            throw e
        }
    }
}
