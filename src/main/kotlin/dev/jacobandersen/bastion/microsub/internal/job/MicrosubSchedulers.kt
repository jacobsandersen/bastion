package dev.jacobandersen.bastion.microsub.internal.job

import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.entry.EntryRepository
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class EntryRetentionService(
    private val entries: EntryRepository,
    private val config: MicrosubConfig,
) {
    /** JobRunr entry point: deletes read or removed entries not seen in their feed within the retention window. */
    fun purge() {
        val deleted = entries.purge(Instant.now().minus(config.retention))
        if (deleted > 0) logger.info { "Purged $deleted old Microsub entries" }
    }
}

/** Fixed recurring job ids, so each occurrence runs on one replica only. */
@Component
class MicrosubSchedulers(
    private val jobScheduler: JobScheduler,
    private val refresh: FeedRefreshService,
    private val retention: EntryRetentionService,
    private val config: MicrosubConfig,
) {
    @PostConstruct
    fun schedule() {
        jobScheduler.scheduleRecurrently(POLL_JOB_ID, config.schedulerInterval) { refresh.pollDue() }
        jobScheduler.scheduleRecurrently(RETENTION_JOB_ID, RETENTION_INTERVAL) { retention.purge() }
    }

    companion object {
        const val POLL_JOB_ID = "microsub-feed-poll"
        const val RETENTION_JOB_ID = "microsub-entry-retention"
        private val RETENTION_INTERVAL: Duration = Duration.ofHours(6)
    }
}
