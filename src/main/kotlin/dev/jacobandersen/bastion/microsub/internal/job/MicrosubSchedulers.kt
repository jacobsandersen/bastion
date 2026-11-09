package dev.jacobandersen.bastion.microsub.internal.job

import dev.jacobandersen.bastion.microsub.internal.config.MicrosubConfig
import dev.jacobandersen.bastion.microsub.internal.feed.FeedRefreshService
import jakarta.annotation.PostConstruct
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Component

/** Fixed recurring job ids, so each occurrence runs on one replica only. */
@Component
class MicrosubSchedulers(
    private val jobScheduler: JobScheduler,
    private val refresh: FeedRefreshService,
    private val config: MicrosubConfig,
) {
    @PostConstruct
    fun schedule() {
        jobScheduler.scheduleRecurrently(POLL_JOB_ID, config.schedulerInterval) { refresh.pollDue() }
        // JobRunr persists recurring jobs, so the removed purge job would otherwise keep failing every 6 hours.
        // Deleting an unknown id is a no-op. Drop this call once every deployment has run it.
        jobScheduler.deleteRecurringJob(REMOVED_RETENTION_JOB_ID)
    }

    companion object {
        const val POLL_JOB_ID = "microsub-feed-poll"
        const val REMOVED_RETENTION_JOB_ID = "microsub-entry-retention"
    }
}
