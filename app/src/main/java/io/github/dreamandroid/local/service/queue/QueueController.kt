package io.github.dreamandroid.local.service.queue

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.dreamandroid.local.service.QueueRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin wrapper around [WorkManager] for queue lifecycle control.
 *
 * The [GenerationWorker] runs as a long-lived worker that sequentially
 * processes pending tasks from [QueueRepository]. This controller provides:
 * - [start]: enqueue worker if not already running
 * - [stop]: cancel worker + cancel all pending tasks
 * - [observeState]: Flow of WorkInfo for UI binding
 * - [hasActiveWorker]: Check if a generation-queue worker is currently active
 *
 * This object holds no mutable state — it is purely a namespace for
 * WorkManager operations.
 */
object QueueController {

    private const val TAG = "QueueController"

    /**
     * Start (or resume) queue processing.
     * Idempotent — if a worker is already running this is a no-op.
     */
    fun start(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val queueRepository = QueueRepository.getInstance(context)

        val workRequest = OneTimeWorkRequestBuilder<GenerationWorker>()
            .addTag(GenerationWorker.WORK_TAG)
            .setConstraints(
                Constraints.Builder()
                    // No network constraint since backend is localhost
                    .build(),
            )
            .build()

        workManager.enqueueUniqueWork(
            GenerationWorker.WORK_TAG,
            ExistingWorkPolicy.KEEP, // Don't start a new one if already running
            workRequest,
        )
        queueRepository.setQueuePaused(false)
        Log.d(TAG, "Unique work enqueued with KEEP policy")
    }

    /**
     * Pause all queue processing.
     *
     * **Order (race-free):**
     * 1. Cancel the WorkManager worker
     * 2. Wait for worker confirmation (CANCELLED state, 5s timeout)
     * 3. Reset any PROCESSING task back to PENDING
     *
     * PENDING tasks remain PENDING (not cancelled) so the user can resume later.
     * The backend service is NOT stopped — only the queue worker is cancelled.
     */
    suspend fun stop(context: Context) {
        val queueRepository = QueueRepository.getInstance(context)

        try {
            val workManager = WorkManager.getInstance(context)

            // Step 1: Cancel the worker
            workManager.cancelAllWorkByTag(GenerationWorker.WORK_TAG)
            Log.d(TAG, "Worker cancellation requested")

            // Step 2: Wait for worker to confirm cancellation (with timeout)
            val confirmed = withTimeoutOrNull(5000L) {
                workManager.getWorkInfosForUniqueWorkLiveData(GenerationWorker.WORK_TAG)
                    .asFlow()
                    .first { infos ->
                        infos.all {
                            it.state == WorkInfo.State.CANCELLED ||
                            it.state == WorkInfo.State.SUCCEEDED ||
                            it.state == WorkInfo.State.FAILED
                        }
                    }
            }
            if (confirmed == null) {
                Log.w(TAG, "Worker did not confirm cancellation within 5s — proceeding anyway")
            } else {
                Log.d(TAG, "Worker confirmed termination")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during worker cancellation", e)
        }

        // Step 3: Reset any stale PROCESSING task to PENDING
        // (Worker may have already done this via CancellationException handler,
        //  but we ensure it here as a safety net)
        queueRepository.resetProcessingToPending()
        queueRepository.setProcessingActive(false)
        queueRepository.setQueuePaused(true)
        Log.d(TAG, "Queue processing paused")
    }

    /**
     * Check whether a generation-queue worker is currently enqueued or running.
     * Used by the UI to avoid showing "start queue" when already active.
     */
    suspend fun hasActiveWorker(context: Context): Boolean {
        val workManager = WorkManager.getInstance(context)
        return try {
            val infos = withTimeoutOrNull(2000L) {
                workManager.getWorkInfosForUniqueWorkLiveData(GenerationWorker.WORK_TAG)
                    .asFlow()
                    .first()
            }
            infos?.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Observe the WorkInfo for the queue worker.
     * Emits only when a WorkInfo is available (null is filtered out).
     */
    fun observeState(context: Context): Flow<WorkInfo?> {
        val workManager = WorkManager.getInstance(context)
        return workManager.getWorkInfosForUniqueWorkLiveData(GenerationWorker.WORK_TAG)
            .asFlow()
            .mapNotNull { infos ->
                // infos is List<WorkInfo>; we only care about the first (only) one
                infos.firstOrNull()
            }
    }

    /** Convert WorkInfo state to a user-friendly status string */
    fun stateLabel(info: WorkInfo?): String = when (info?.state) {
        WorkInfo.State.ENQUEUED -> "ENQUEUED"
        WorkInfo.State.RUNNING -> "RUNNING"
        WorkInfo.State.SUCCEEDED -> "DONE"
        WorkInfo.State.FAILED -> "FAILED"
        WorkInfo.State.BLOCKED -> "BLOCKED"
        WorkInfo.State.CANCELLED -> "CANCELLED"
        null -> "IDLE"
    }
}

/** Convert LiveData to cold Flow via callbackFlow */
private fun <T> LiveData<T>.asFlow(): Flow<T> = callbackFlow {
    val observer = Observer<T> { value -> if (value != null) trySend(value) }
    observeForever(observer)
    awaitClose { removeObserver(observer) }
}
