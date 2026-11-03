package dev.jacobandersen.bastion.post.internal.types

import jakarta.annotation.PostConstruct
import org.jobrunr.scheduling.JobScheduler
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Runs post type reclassification at startup and periodically. The recurring
 * job has a fixed JobRunr ID, so only one replica runs each occurrence. The
 * state row's claim guards the startup enqueue against concurrent replicas.
 */
@Component
class PostTypeReclassificationScheduler(
    private val jobScheduler: JobScheduler,
    private val reclassificationService: PostTypeReclassificationService,
) {
    @PostConstruct
    fun scheduleReconciliation() {
        jobScheduler.scheduleRecurrently(RECURRING_JOB_ID, INTERVAL) {
            reclassificationService.reconcile()
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        reclassificationService.initialize()
        jobScheduler.enqueue { reclassificationService.reconcile() }
    }

    companion object {
        const val RECURRING_JOB_ID = "post-type-reclassification"
        val INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
