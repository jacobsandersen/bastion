package dev.jacobandersen.bastion.websub.internal.subscriber

import dev.jacobandersen.bastion.websub.internal.config.WebsubConfig
import jakarta.annotation.PostConstruct
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Component

/** Runs [WebsubSubscriber.renewDue] on a fixed recurring job id, so one replica runs each occurrence. */
@Component
class WebsubRenewalScheduler(
    private val jobScheduler: JobScheduler,
    private val subscriber: WebsubSubscriber,
    private val config: WebsubConfig,
) {
    @PostConstruct
    fun schedule() {
        jobScheduler.scheduleRecurrently(RECURRING_JOB_ID, config.subscriber.renewalInterval) {
            subscriber.renewDue()
        }
    }

    companion object {
        const val RECURRING_JOB_ID = "websub-subscription-renewal"
    }
}
