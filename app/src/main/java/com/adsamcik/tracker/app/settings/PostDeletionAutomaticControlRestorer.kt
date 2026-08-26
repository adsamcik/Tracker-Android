package com.adsamcik.tracker.app.settings

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.api.AutomaticControlRecoveryResult
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Durable retry owner for reopening collected-data writers and automatic Activity control.
 *
 * Scheduling is safe while the process deletion barrier is still closed. WorkManager retains the
 * essential writer and Activity-capture-latch reopening obligations across process death, while
 * optional automatic control has a bounded retry budget for each collected-data epoch.
 */
@Singleton
class PostDeletionAutomaticControlRestorer @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val readyRearm = PostDeletionReadyRearm()

	fun schedule(collectedDataEpoch: Long) {
		require(collectedDataEpoch >= 0L)
		readyRearm.schedule(collectedDataEpoch, ::enqueueRecovery)
	}

	internal fun preserveUntilStartupReady(
		collectedDataEpoch: Long,
		startupGeneration: Long,
	): BlockedPostDeletionRecoveryDisposition = readyRearm.preserveBlockedRecovery(
		collectedDataEpoch = collectedDataEpoch,
		startupGeneration = startupGeneration,
	)

	/**
	 * Re-enqueues a worker that stopped at a permanent startup block without polling that block.
	 * Returns false when this Ready generation has no matching process-local obligation, or when
	 * WorkManager rejected the enqueue and the obligation was retained for a later Ready signal.
	 */
	internal fun onAuthoritativeStartupReady(startupGeneration: Long): Boolean = try {
		readyRearm.onStartupReady(startupGeneration, ::enqueueRecovery)
	} catch (_: Exception) {
		false
	}

	internal fun onRecoveryHandoffCompleted(collectedDataEpoch: Long) {
		readyRearm.onRecoveryHandoffCompleted(collectedDataEpoch)
	}

	private fun enqueueRecovery(collectedDataEpoch: Long) {
		val request = OneTimeWorkRequestBuilder<PostDeletionRecoveryWorker>()
			.setInputData(workDataOf(PostDeletionRecoveryWorker.COLLECTED_DATA_EPOCH_KEY to collectedDataEpoch))
			.setBackoffCriteria(
				BackoffPolicy.EXPONENTIAL,
				MINIMUM_BACKOFF_SECONDS,
				TimeUnit.SECONDS,
			)
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			request,
		)
	}

	companion object {
		internal const val UNIQUE_WORK_NAME = "TRACKER.POST_DELETION_RECOVERY"
		private const val MINIMUM_BACKOFF_SECONDS = 30L
	}
}

@HiltWorker
class PostDeletionRecoveryWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val startupGate: TrackingStartupGate,
	private val deletionBarrier: TrackingStartupDeletionBarrier,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val writerQuiescer: CollectedDataWriterQuiescer,
	private val activityRegistrationArbiterProvider: Provider<ActivityRegistrationArbiter>,
	private val automaticControlRestorer: PostDeletionAutomaticControlRestorer,
) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		val expectedEpoch = inputData.getLong(COLLECTED_DATA_EPOCH_KEY, MISSING_EPOCH)
		if (expectedEpoch < 0L) return Result.failure()
		val startupGeneration = startupGate.currentGeneration
		val outcome = try {
			runPostDeletionRecovery(
				expectedEpoch = expectedEpoch,
				currentEpoch = { lifecycleStore.snapshot().epoch },
				startupGeneration = startupGeneration,
				currentStartupGeneration = { startupGate.currentGeneration },
				isDeletionClosed = { deletionBarrier.isClosed },
				isStartupReady = { startupGate.isReady },
				reconcileStartup = { startupGate.reconcile() },
				withReadyGenerationOperation = { generation, operation ->
					startupGate.withReadyGenerationOperation(generation, operation)
				},
				resumeWriters = writerQuiescer::resume,
				resumeActivityArbiter = {
					activityRegistrationArbiterProvider.get()
						.resumeAfterCollectedDataDeletion()
				},
				reconcileAutomaticControl = {
					BackgroundTrackingApi.reconcileAutomaticControlDemandAfterStartup(
						applicationContext,
					)
				},
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			PostDeletionRecoveryOutcome.DURABLE_RETRY
		}
		return when (outcome) {
			PostDeletionRecoveryOutcome.WAITING_FOR_STARTUP_READY -> when (
				automaticControlRestorer.preserveUntilStartupReady(
					collectedDataEpoch = expectedEpoch,
					startupGeneration = startupGeneration,
				)
			) {
				BlockedPostDeletionRecoveryDisposition.RETRY_CURRENT_WORK_ONCE -> Result.retry()
				BlockedPostDeletionRecoveryDisposition.PENDING_READY,
				BlockedPostDeletionRecoveryDisposition.STALE_EPOCH,
				-> Result.success()
			}
			PostDeletionRecoveryOutcome.COMPLETE,
			PostDeletionRecoveryOutcome.OPTIONAL_CONTROL_RETRY,
			-> {
				automaticControlRestorer.onRecoveryHandoffCompleted(expectedEpoch)
				if (outcome.shouldRetry(runAttemptCount)) Result.retry() else Result.success()
			}
			PostDeletionRecoveryOutcome.DURABLE_RETRY -> Result.retry()
		}
	}

	companion object {
		internal const val COLLECTED_DATA_EPOCH_KEY = "collected_data_epoch"
		private const val MISSING_EPOCH = -1L
	}
}

internal enum class PostDeletionRecoveryOutcome {
	COMPLETE,
	/** A permanent startup block waits for an explicit same-process Ready signal without polling. */
	WAITING_FOR_STARTUP_READY,
	/** Storage, startup, writer, and Activity-capture-latch reopening retain retry ownership. */
	DURABLE_RETRY,
	/** Activity provider/control reconciliation is optional and has a battery-bounded retry budget. */
	OPTIONAL_CONTROL_RETRY,
}

internal fun PostDeletionRecoveryOutcome.shouldRetry(runAttemptCount: Int): Boolean = when (this) {
	PostDeletionRecoveryOutcome.COMPLETE,
	PostDeletionRecoveryOutcome.WAITING_FOR_STARTUP_READY,
	-> false
	PostDeletionRecoveryOutcome.DURABLE_RETRY -> true
	// Exhausting this deletion-epoch flight does not suppress future authority changes.
	// BackgroundTrackingApi's policy/preference observers and foreground permission revalidation
	// remain live and schedule their own fresh bounded automatic-control recovery when needed.
	PostDeletionRecoveryOutcome.OPTIONAL_CONTROL_RETRY ->
		runAttemptCount + 1 < OPTIONAL_CONTROL_MAX_ATTEMPTS
}

internal suspend fun runPostDeletionRecovery(
	expectedEpoch: Long,
	currentEpoch: suspend () -> Long,
	startupGeneration: Long,
	currentStartupGeneration: () -> Long,
	isDeletionClosed: () -> Boolean,
	isStartupReady: () -> Boolean,
	reconcileStartup: suspend () -> TrackingStartupResult,
	withReadyGenerationOperation: suspend (
		Long,
		suspend () -> AutomaticControlRecoveryResult?,
	) -> AutomaticControlRecoveryResult? =
		{ _, operation -> operation() },
	resumeWriters: () -> Unit,
	resumeActivityArbiter: suspend () -> Unit,
	reconcileAutomaticControl: suspend () -> AutomaticControlRecoveryResult,
): PostDeletionRecoveryOutcome {
	if (currentEpoch() != expectedEpoch) return PostDeletionRecoveryOutcome.COMPLETE
	if (isDeletionClosed()) return PostDeletionRecoveryOutcome.DURABLE_RETRY
	when (reconcileStartup()) {
		is TrackingStartupResult.Ready -> Unit
		is TrackingStartupResult.RetryableFailure ->
			return PostDeletionRecoveryOutcome.DURABLE_RETRY
		is TrackingStartupResult.Blocked ->
			return PostDeletionRecoveryOutcome.WAITING_FOR_STARTUP_READY
	}
	if (!isStartupReady() || isDeletionClosed() ||
		currentStartupGeneration() != startupGeneration
	) {
		return PostDeletionRecoveryOutcome.DURABLE_RETRY
	}
	if (currentEpoch() != expectedEpoch) return PostDeletionRecoveryOutcome.COMPLETE

	// These operations may open collected Room or schedule Room workers. Serialize their final
	// epoch/generation check and resume with deletion closure so a new deletion cannot win between
	// the check and either side effect. An optional retry deliberately repeats this idempotent
	// handoff: WorkManager may die between either resume and control reconciliation, and there is no
	// durable receipt proving which process-local callback completed. The writer owner uses unique
	// work/update scheduling and consumes its restore flags on the first resume; the Activity arbiter
	// mutex reconciles the same durable desired state without replacing an identical registration.
	val controlRecovery = try {
		withReadyGenerationOperation(startupGeneration) {
			if (currentEpoch() != expectedEpoch || isDeletionClosed() ||
				!isStartupReady() || currentStartupGeneration() != startupGeneration
			) {
				null
			} else {
				resumeWriters()
				// This reopens process-local deletion latches used by captured Activity as well as
				// optional automatic control. Failure therefore retains durable retry ownership.
				resumeActivityArbiter()
				try {
					reconcileAutomaticControl()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					AutomaticControlRecoveryResult.RETRYABLE
				}
			}
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		return PostDeletionRecoveryOutcome.DURABLE_RETRY
	} ?: return PostDeletionRecoveryOutcome.DURABLE_RETRY

	// Deletion closure waits for the protected handoff above; if it wins after the handoff it owns
	// the matching quiesce/removal. Optional control containment is terminal while explicitly
	// transient failures retain retry work.
	if (currentEpoch() != expectedEpoch || isDeletionClosed() ||
		!isStartupReady() || currentStartupGeneration() != startupGeneration
	) {
		return PostDeletionRecoveryOutcome.DURABLE_RETRY
	}
	return when (controlRecovery) {
		AutomaticControlRecoveryResult.ACCEPTED,
		AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED ->
			PostDeletionRecoveryOutcome.COMPLETE
		AutomaticControlRecoveryResult.RETRYABLE ->
			PostDeletionRecoveryOutcome.OPTIONAL_CONTROL_RETRY
	}
}

private const val OPTIONAL_CONTROL_MAX_ATTEMPTS = 3

internal enum class BlockedPostDeletionRecoveryDisposition {
	PENDING_READY,
	RETRY_CURRENT_WORK_ONCE,
	STALE_EPOCH,
}

/**
 * Process-local bridge between a Blocked recovery worker and an explicit startup repair.
 *
 * Process death intentionally clears this state: the writer and Activity latches it represents
 * are also process-local and reconstruct open. WorkManager remains the durable owner for failures
 * that occur after startup becomes Ready.
 */
internal class PostDeletionReadyRearm {
	private val monitor = Any()
	private var latestScheduledEpoch = NO_EPOCH
	private var pendingRecovery: PendingRecovery? = null
	private var latestReadyGeneration = NO_GENERATION
	private var readyRaceRetryConsumedGeneration = NO_GENERATION

	fun schedule(
		collectedDataEpoch: Long,
		enqueue: (Long) -> Unit,
	): Boolean = synchronized(monitor) {
		if (collectedDataEpoch < latestScheduledEpoch) return@synchronized false
		latestScheduledEpoch = collectedDataEpoch
		pendingRecovery = null
		enqueue(collectedDataEpoch)
		true
	}

	fun preserveBlockedRecovery(
		collectedDataEpoch: Long,
		startupGeneration: Long,
	): BlockedPostDeletionRecoveryDisposition = synchronized(monitor) {
		if (collectedDataEpoch != latestScheduledEpoch) {
			return@synchronized BlockedPostDeletionRecoveryDisposition.STALE_EPOCH
		}
		if (startupGeneration == latestReadyGeneration &&
			readyRaceRetryConsumedGeneration != startupGeneration
		) {
			readyRaceRetryConsumedGeneration = startupGeneration
			return@synchronized BlockedPostDeletionRecoveryDisposition.RETRY_CURRENT_WORK_ONCE
		}
		pendingRecovery = PendingRecovery(collectedDataEpoch, startupGeneration)
		BlockedPostDeletionRecoveryDisposition.PENDING_READY
	}

	fun onStartupReady(
		startupGeneration: Long,
		enqueue: (Long) -> Unit,
	): Boolean = synchronized(monitor) {
		latestReadyGeneration = startupGeneration
		readyRaceRetryConsumedGeneration = NO_GENERATION
		val pending = pendingRecovery ?: return@synchronized false
		if (pending.collectedDataEpoch != latestScheduledEpoch ||
			pending.startupGeneration != startupGeneration
		) {
			if (pending.collectedDataEpoch != latestScheduledEpoch) pendingRecovery = null
			return@synchronized false
		}
		pendingRecovery = null
		try {
			enqueue(pending.collectedDataEpoch)
		} catch (error: Exception) {
			pendingRecovery = pending
			throw error
		}
		true
	}

	fun onRecoveryHandoffCompleted(collectedDataEpoch: Long) = synchronized(monitor) {
		if (pendingRecovery?.collectedDataEpoch == collectedDataEpoch) pendingRecovery = null
	}

	private data class PendingRecovery(
		val collectedDataEpoch: Long,
		val startupGeneration: Long,
	)

	private companion object {
		const val NO_EPOCH = -1L
		const val NO_GENERATION = -1L
	}
}
